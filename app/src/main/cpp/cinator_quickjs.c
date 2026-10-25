/* Fresh, bounded QuickJS runtime; intentionally no quickjs-libc or host bindings. */
#include <jni.h>
#include <stdint.h>
#include <stddef.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include "quickjs.h"

#define HEAP_LIMIT (16U * 1024U * 1024U)
#define STACK_LIMIT (256U * 1024U)
#define SOURCE_LIMIT 131072
#define DOCUMENT_LIMIT 65536
#define OUTPUT_LIMIT 262144
#define JOB_LIMIT 1024U

typedef struct { size_t used; int oom; int expired; int64_t deadline_ns; } Budget;
typedef union { max_align_t alignment; struct { size_t size; } data; } Allocation;

static int64_t monotonic_ns(void) {
    struct timespec now;
    if (clock_gettime(CLOCK_MONOTONIC, &now) != 0) return -1;
    return (int64_t)now.tv_sec * 1000000000LL + now.tv_nsec;
}

static void *bounded_malloc(JSMallocState *state, size_t size) {
    Budget *budget = state->opaque;
    if (!size) return NULL;
    if (size > HEAP_LIMIT - sizeof(Allocation) || budget->used > HEAP_LIMIT - sizeof(Allocation) - size) {
        budget->oom = 1; return NULL;
    }
    Allocation *block = malloc(sizeof(*block) + size);
    if (!block) { budget->oom = 1; return NULL; }
    block->data.size = size;
    budget->used += sizeof(*block) + size;
    state->malloc_size += sizeof(*block) + size;
    state->malloc_count++;
    return block + 1;
}

static void bounded_free(JSMallocState *state, void *ptr) {
    if (!ptr) return;
    Budget *budget = state->opaque;
    Allocation *block = (Allocation *)ptr - 1;
    size_t total = sizeof(*block) + block->data.size;
    budget->used -= total;
    state->malloc_size -= total;
    state->malloc_count--;
    free(block);
}

static void *bounded_realloc(JSMallocState *state, void *ptr, size_t size) {
    if (!ptr) return bounded_malloc(state, size);
    if (!size) { bounded_free(state, ptr); return NULL; }
    Budget *budget = state->opaque;
    Allocation *old = (Allocation *)ptr - 1;
    size_t old_size = old->data.size;
    if (size > HEAP_LIMIT - sizeof(Allocation) ||
        budget->used - old_size > HEAP_LIMIT - size) {
        budget->oom = 1; return NULL;
    }
    Allocation *block = realloc(old, sizeof(*block) + size);
    if (!block) { budget->oom = 1; return NULL; }
    block->data.size = size;
    budget->used = budget->used - old_size + size;
    state->malloc_size = state->malloc_size - old_size + size;
    return block + 1;
}

static size_t bounded_usable_size(const void *ptr) {
    return ptr ? ((const Allocation *)ptr - 1)->data.size : 0;
}

static int interrupt(JSRuntime *runtime, void *opaque) {
    (void)runtime;
    Budget *budget = opaque;
    int64_t now = monotonic_ns();
    if (now < 0 || now >= budget->deadline_ns) budget->expired = 1;
    return budget->expired;
}

/* Reject malformed UTF-8, including overlong forms and surrogate code points. */
static int valid_utf8(const unsigned char *bytes, size_t size) {
    size_t i = 0;
    while (i < size) {
        uint32_t code = bytes[i++];
        if (code < 0x80) continue;
        unsigned int remaining;
        uint32_t minimum;
        if (code >= 0xc2 && code <= 0xdf) { remaining = 1; minimum = 0x80; code &= 0x1f; }
        else if (code >= 0xe0 && code <= 0xef) { remaining = 2; minimum = 0x800; code &= 0x0f; }
        else if (code >= 0xf0 && code <= 0xf4) { remaining = 3; minimum = 0x10000; code &= 7; }
        else return 0;
        if (remaining > size - i) return 0;
        while (remaining--) {
            unsigned char next = bytes[i++];
            if ((next & 0xc0) != 0x80) return 0;
            code = (code << 6) | (next & 0x3f);
        }
        if (code < minimum || code > 0x10ffff || (code >= 0xd800 && code <= 0xdfff)) return 0;
    }
    return 1;
}

static int canonical_uuid(const char *uuid) {
    for (size_t i = 0; i < 36; i++) {
        if (i == 8 || i == 13 || i == 18 || i == 23) { if (uuid[i] != '-') return 0; }
        else if (!((uuid[i] >= '0' && uuid[i] <= '9') || (uuid[i] >= 'a' && uuid[i] <= 'f'))) return 0;
    }
    return 1;
}

static void throw_sanitized(JNIEnv *env, const char *category) {
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
    jclass type = (*env)->FindClass(env, "java/lang/IllegalStateException");
    if (type) {
        (*env)->ThrowNew(env, type, category);
        (*env)->DeleteLocalRef(env, type);
    }
}

/* Copy once without holding JVM array pins while arbitrary JS executes. */
static char *copy_bytes(JNIEnv *env, jbyteArray array, jsize size) {
    char *copy = malloc((size_t)size + 1);
    if (!copy) return NULL;
    (*env)->GetByteArrayRegion(env, array, 0, size, (jbyte *)copy);
    if ((*env)->ExceptionCheck(env)) { free(copy); return NULL; }
    copy[size] = '\0';
    return copy;
}

JNIEXPORT jbyteArray JNICALL Java_com_cinator_app_engines_nuvio_runtime_NativeQuickJs_evaluate(
    JNIEnv *env, jobject self, jbyteArray source_array, jbyteArray document_array,
    jbyteArray uuid_array, jint height, jint timeout_ms) {
    (void)self;
    const char *error = NULL;
    char *source = NULL, *document = NULL, *uuid = NULL;
    const char *output = NULL;
    jbyteArray result = NULL;
    JSRuntime *runtime = NULL;
    JSContext *context = NULL;
    JSValue global = JS_UNDEFINED, source_value = JS_UNDEFINED, evaluated = JS_UNDEFINED;
    Budget budget = {0};
    if (!source_array || !document_array || !uuid_array || height < 144 || height > 2160 || timeout_ms < 50 || timeout_ms > 2000) {
        error = "INVALID_INPUT"; goto cleanup;
    }
    jsize source_size = (*env)->GetArrayLength(env, source_array);
    jsize document_size = (*env)->GetArrayLength(env, document_array);
    jsize uuid_size = (*env)->GetArrayLength(env, uuid_array);
    if ((*env)->ExceptionCheck(env) || source_size < 0 || source_size > SOURCE_LIMIT ||
        document_size < 0 || document_size > DOCUMENT_LIMIT || uuid_size != 36) {
        error = "INVALID_INPUT"; goto cleanup;
    }
    source = copy_bytes(env, source_array, source_size);
    document = copy_bytes(env, document_array, document_size);
    uuid = copy_bytes(env, uuid_array, uuid_size);
    if (!source || !document || !uuid) { error = "OUT_OF_MEMORY"; goto cleanup; }
    if (!valid_utf8((const unsigned char *)source, (size_t)source_size) ||
        !valid_utf8((const unsigned char *)document, (size_t)document_size) ||
        memchr(source, 0, (size_t)source_size) || !canonical_uuid(uuid)) {
        error = "INVALID_INPUT"; goto cleanup;
    }
    int64_t start = monotonic_ns();
    if (start < 0) { error = "ENGINE_ERROR"; goto cleanup; }
    budget.deadline_ns = start + (int64_t)timeout_ms * 1000000LL;
    const JSMallocFunctions allocator = {bounded_malloc, bounded_free, bounded_realloc, bounded_usable_size};
    runtime = JS_NewRuntime2(&allocator, &budget);
    if (!runtime) { error = "OUT_OF_MEMORY"; goto cleanup; }
    JS_SetMemoryLimit(runtime, HEAP_LIMIT);
    JS_SetMaxStackSize(runtime, STACK_LIMIT);
    JS_SetInterruptHandler(runtime, interrupt, &budget);
    context = JS_NewContext(runtime);
    if (!context) { error = "OUT_OF_MEMORY"; goto cleanup; }
    global = JS_GetGlobalObject(context);
    if (JS_IsException(global)) goto js_error;
    JSValue value = JS_NewStringLen(context, document, (size_t)document_size);
    if (JS_IsException(value)) goto js_error;
    if (JS_DefinePropertyValueStr(context, global, "__parentDocument", value, 0) < 0) goto js_error;
    value = JS_NewStringLen(context, uuid, 36);
    if (JS_IsException(value)) goto js_error;
    if (JS_DefinePropertyValueStr(context, global, "__expectedUuid", value, 0) < 0) goto js_error;
    if (JS_DefinePropertyValueStr(context, global, "__preferredHeight", JS_NewInt32(context, height), 0) < 0) goto js_error;
    /* JS_Eval returns an owned value, even for source with a completion value.
       Release it before invocation and reset so every cleanup path frees once. */
    source_value = JS_Eval(context, source, (size_t)source_size, "reviewed-source", JS_EVAL_TYPE_GLOBAL);
    if (JS_IsException(source_value)) goto js_error;
    JS_FreeValue(context, source_value);
    source_value = JS_UNDEFINED;
    static const char invocation[] = "module.exports.getStreams(__parentDocument, __expectedUuid, __preferredHeight)";
    evaluated = JS_Eval(context, invocation, sizeof(invocation) - 1, "reviewed-invocation", JS_EVAL_TYPE_GLOBAL);
    if (JS_IsException(evaluated)) goto js_error;
    if (interrupt(runtime, &budget)) { error = "TIMEOUT"; goto cleanup; }
    if (budget.oom) { error = "OUT_OF_MEMORY"; goto cleanup; }
    /* PromiseState returns -1 for non-promises; never inspect/call a "then"
       property. Synchronous results retain their original no-job-pump behavior.
       All values, jobs and cleanup stay on this JNI call's sole owning thread. */
    int promise_state = (int)JS_PromiseState(context, evaluated);
    unsigned int jobs = 0;
    while (promise_state == JS_PROMISE_PENDING) {
        /* Check between jobs too: short jobs may never trigger the VM interrupt. */
        if (interrupt(runtime, &budget)) { error = "TIMEOUT"; goto cleanup; }
        if (budget.oom) { error = "OUT_OF_MEMORY"; goto cleanup; }
        if (!JS_IsJobPending(runtime)) { error = "PENDING_PROMISE"; goto cleanup; }
        if (jobs >= JOB_LIMIT) { error = "JOB_LIMIT"; goto cleanup; }
        /* The job context is borrowed, not a new context to free. This runtime
           has only our context; drain its exception without invoking JS text. */
        JSContext *job_context = NULL;
        int job_status = JS_ExecutePendingJob(runtime, &job_context);
        jobs++;
        if (job_status < 0 && job_context) {
            JSValue exception = JS_GetException(job_context);
            JS_FreeValue(job_context, exception);
        }
        if (interrupt(runtime, &budget)) { error = "TIMEOUT"; goto cleanup; }
        if (budget.oom) { error = "OUT_OF_MEMORY"; goto cleanup; }
        if (job_status < 0) goto js_error;
        if (job_status == 0) { error = "PENDING_PROMISE"; goto cleanup; }
        promise_state = (int)JS_PromiseState(context, evaluated);
    }
    if (promise_state == JS_PROMISE_REJECTED) {
        /* Never read/stringify the rejection reason (including its getters). */
        error = "ENGINE_ERROR"; goto cleanup;
    }
    if (promise_state == JS_PROMISE_FULFILLED) {
        /* PromiseResult returns an owned duplicate, not a borrowed value.
           Keep it alive before freeing the owned promise; cleanup frees it once. */
        JSValue fulfillment = JS_PromiseResult(context, evaluated);
        JS_FreeValue(context, evaluated);
        evaluated = fulfillment;
    }
    if (!JS_IsString(evaluated)) { error = "INVALID_RESULT"; goto cleanup; }
    size_t output_size = 0;
    output = JS_ToCStringLen(context, &output_size, evaluated);
    if (!output) goto js_error;
    if (output_size > OUTPUT_LIMIT) { error = "OUTPUT_LIMIT"; goto cleanup; }
    if (interrupt(runtime, &budget)) { error = "TIMEOUT"; goto cleanup; }
    result = (*env)->NewByteArray(env, (jsize)output_size);
    if (!result || (*env)->ExceptionCheck(env)) { error = "OUT_OF_MEMORY"; goto cleanup; }
    (*env)->SetByteArrayRegion(env, result, 0, (jsize)output_size, (const jbyte *)output);
    if ((*env)->ExceptionCheck(env)) { error = "ENGINE_ERROR"; goto cleanup; }
    goto cleanup;
js_error:
    error = budget.expired ? "TIMEOUT" : budget.oom ? "OUT_OF_MEMORY" : "ENGINE_ERROR";
cleanup:
    if (context) {
        if (output) JS_FreeCString(context, output);
        JS_FreeValue(context, evaluated);
        JS_FreeValue(context, source_value);
        JS_FreeValue(context, global);
        /* Never stringify exception values: getters, messages and stack are untrusted. */
        JSValue exception = JS_GetException(context);
        JS_FreeValue(context, exception);
        JS_FreeContext(context);
    }
    if (runtime) JS_FreeRuntime(runtime);
    free(uuid); free(document); free(source);
    if (error) {
        if (result) (*env)->DeleteLocalRef(env, result);
        throw_sanitized(env, error);
        return NULL;
    }
    return result;
}

JNIEXPORT jstring JNICALL Java_com_cinator_app_engines_nuvio_runtime_NativeQuickJs_version(JNIEnv *env, jobject self) {
    (void)self;
    /* ASCII constant only; evaluation never uses NewStringUTF. */
    return (*env)->NewStringUTF(env, "QuickJS " CONFIG_VERSION " (04be246)");
}

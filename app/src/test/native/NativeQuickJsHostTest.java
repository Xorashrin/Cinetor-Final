package com.cinator.app.engines.nuvio.runtime;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/** Calls the actual production JNI engine via the Kotlin object ABI twin. */
public class NativeQuickJsHostTest {
    private static final String ID = "d5382e6e-7c96-42bb-96e8-155fbe765d5a";
    private static final byte[] UUID = utf8(ID);
    private static byte[] utf8(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    private static String script(String body) { return "var module={exports:{getStreams:function(d,u,h){" + body + "}}};"; }
    private static byte[] evaluate(String source, String document, int height) {
        return NativeQuickJs.INSTANCE.evaluate(utf8(source), utf8(document), UUID, height, 1000);
    }
    private static void equal(String expected, byte[] actual) {
        if (!Arrays.equals(utf8(expected), actual)) throw new AssertionError("Result bytes differ");
    }
    private static void expectError(String expected, byte[] source, byte[] document, byte[] uuid, int height, int timeoutMs) {
        try {
            NativeQuickJs.INSTANCE.evaluate(source, document, uuid, height, timeoutMs);
            throw new AssertionError("Expected sanitized " + expected);
        } catch (IllegalStateException failure) {
            if (!expected.equals(failure.getMessage())) throw new AssertionError("Wrong error category", failure);
        }
    }
    private static void error(String expected, String source, int timeout) {
        expectError(expected, utf8(source), utf8("{}"), UUID, 720, timeout);
        equal("42", evaluate(script("return String(6*7);"), "{}", 720));
    }
    public static void main(String[] args) throws Exception {
        String name = args.length == 0 ? "invalid" : args[0];
        switch (name) {
            case "invalid":
                expectError("INVALID_INPUT", null, utf8("{}"), UUID, 720, 100);
                expectError("INVALID_INPUT", utf8(""), null, UUID, 720, 100);
                expectError("INVALID_INPUT", utf8(""), utf8("{}"), null, 720, 100);
                expectError("INVALID_INPUT", new byte[131073], utf8("{}"), UUID, 720, 100);
                expectError("INVALID_INPUT", utf8(""), new byte[65537], UUID, 720, 100);
                expectError("INVALID_INPUT", utf8(""), utf8("{}"), new byte[35], 720, 100);
                expectError("INVALID_INPUT", utf8(""), utf8("{}"), utf8(ID.toUpperCase()), 720, 100);
                for (int height : new int[]{143, 2161}) expectError("INVALID_INPUT", utf8(""), utf8("{}"), UUID, height, 100);
                for (int timeout : new int[]{49, 2001}) expectError("INVALID_INPUT", utf8(""), utf8("{}"), UUID, 720, timeout);
                break;
            case "arithmetic":
                equal("42", evaluate(script("return String(6*7);"), "{}", 720));
                if (!"QuickJS 2026-06-04 (04be246)".equals(NativeQuickJs.INSTANCE.version())) throw new AssertionError("Wrong source pin");
                break;
            case "utf8":
                String document = "বাংলা 🎬\u0000'; throw 'not source'; //";
                equal(document, evaluate(script("return d;"), document, 720));
                equal("🎬", evaluate(script("return '🎬';"), "{}", 720));
                for (byte[] malformed : new byte[][]{{(byte)0xc0,(byte)0x80},{(byte)0xed,(byte)0xa0,(byte)0x80},{(byte)0xf4,(byte)0x90,(byte)0x80,(byte)0x80},{(byte)0x80}}) {
                    expectError("INVALID_INPUT", utf8(script("return d;")), malformed, UUID, 720, 100);
                    expectError("INVALID_INPUT", malformed, utf8("{}"), UUID, 720, 100);
                }
                break;
            case "loop":
                long start = System.nanoTime();
                error("TIMEOUT", "while(true){}", 50);
                if (System.nanoTime()-start > 2_000_000_000L) throw new AssertionError("Deadline was not bounded");
                error("TIMEOUT", script("while(true){}"), 50);
                break;
            case "oom":
                error("OUT_OF_MEMORY", "var a=new ArrayBuffer(32*1024*1024);", 500);
                error("OUT_OF_MEMORY", "var a=[]; for(var i=0;i<100000;i++)a.push(new ArrayBuffer(1024));", 1000);
                // Catching an OOM in JS must not bypass the runtime's failure latch.
                error("OUT_OF_MEMORY", "try{new ArrayBuffer(32*1024*1024)}catch(e){};" + script("return 'ok';"), 500);
                break;
            case "recursion": error("ENGINE_ERROR", "function recurse(){return recurse();} recurse();", 500); break;
            case "sanitized":
                error("ENGINE_ERROR", "throw new Error('PRIVATE_DOCUMENT_MUST_NEVER_LEAK');", 100);
                error("ENGINE_ERROR", "throw {get message(){while(true){}},toString(){while(true){}}};", 100);
                error("ENGINE_ERROR", "this is not valid syntax!!!", 100);
                error("ENGINE_ERROR", "var module={exports:{}};", 100);
                break;
            case "promise_resolve":
                equal("বাংলা 🎬", evaluate(script("return Promise.resolve('বাংলা 🎬');"), "{}", 720));
                break;
            case "promise_async":
                equal("async:42", evaluate("var module={exports:{getStreams:async function(d,u,h){await Promise.resolve(); return 'async:'+await Promise.resolve('42');}}};", "{}", 720));
                equal("chain", evaluate(script("return Promise.resolve().then(()=>Promise.resolve('chain'));"), "{}", 720));
                break;
            case "promise_reject":
                error("ENGINE_ERROR", script("return Promise.reject(new Error('PRIVATE_DOCUMENT_MUST_NEVER_LEAK'));"), 500);
                error("ENGINE_ERROR", script("return Promise.resolve().then(()=>{throw {get message(){while(true){}},toString(){while(true){}}};});"), 100);
                break;
            case "promise_pending":
                long pendingStart = System.nanoTime();
                for (int i=0;i<10;i++) error("PENDING_PROMISE", script("return new Promise(()=>{});"), 1000);
                error("PENDING_PROMISE", script("return Promise.resolve().then(()=>new Promise(()=>{}));"), 1000);
                if (System.nanoTime()-pendingStart > 2_000_000_000L) throw new AssertionError("Empty queue must not wait for a callback");
                break;
            case "promise_jobs":
                long jobsStart = System.nanoTime();
                error("JOB_LIMIT", script("return new Promise(()=>{function again(){Promise.resolve().then(again);} again();});"), 2000);
                if (System.nanoTime()-jobsStart > 2_000_000_000L) throw new AssertionError("Job count was not bounded");
                // Exactly 1024 jobs may settle; a 1025th must not execute.
                equal("done", evaluate(script("return new Promise(resolve=>{var n=0; function next(){if(++n===1024)resolve('done');else Promise.resolve().then(next);} Promise.resolve().then(next);});"), "{}", 720));
                error("JOB_LIMIT", script("return new Promise(resolve=>{var n=0; function next(){if(++n===1025)resolve('done');else Promise.resolve().then(next);} Promise.resolve().then(next);});"), 2000);
                break;
            case "promise_result":
                for (String value : new String[]{"42", "({})", "undefined", "null"}) {
                    error("INVALID_RESULT", script("return Promise.resolve("+value+");"), 500);
                    error("INVALID_RESULT", script("return Promise.resolve().then(()=>"+value+");"), 500);
                }
                error("INVALID_RESULT", script("return {get then(){while(true){}}};"), 100);
                equal("", evaluate(script("return Promise.resolve('');"), "{}", 720));
                equal("x".repeat(262144), evaluate(script("return Promise.resolve().then(()=>'x'.repeat(262144));"), "{}", 720));
                error("OUTPUT_LIMIT", script("return Promise.resolve().then(()=>'x'.repeat(262145));"), 500);
                break;
            case "promise_oom":
                error("OUT_OF_MEMORY", script("return Promise.resolve().then(()=>new ArrayBuffer(32*1024*1024));"), 500);
                error("OUT_OF_MEMORY", script("return Promise.resolve().then(()=>{try{new ArrayBuffer(32*1024*1024)}catch(e){} return 'ok';});"), 500);
                break;
            case "promise_recursion":
                error("ENGINE_ERROR", script("return Promise.resolve().then(()=>{function recurse(){return recurse();}return recurse();});"), 500);
                break;
            case "promise_loop":
                long promiseStart = System.nanoTime();
                error("TIMEOUT", script("return Promise.resolve().then(()=>{while(true){}});"), 50);
                // Many short jobs must share the original deadline, not renew it.
                error("TIMEOUT", script("return new Promise(()=>{function again(){var end=Date.now()+5;while(Date.now()<end){} Promise.resolve().then(again);} Promise.resolve().then(again);});"), 50);
                if (System.nanoTime()-promiseStart > 2_000_000_000L) throw new AssertionError("Promise deadline was not bounded");
                break;
            case "promise_sync":
                // Synchronous strings still return without draining unrelated jobs.
                equal("sync", evaluate(script("Promise.resolve().then(()=>{while(true){}}); return 'sync';"), "{}", 720));
                equal("settled", evaluate(script("return new Promise(resolve=>{Promise.resolve().then(()=>{globalThis.previous=1;resolve('settled');Promise.resolve().then(()=>{while(true){}});});});"), "{}", 720));
                for (int i=0;i<50;i++) equal("clean", evaluate(script("return Promise.resolve().then(()=>typeof previous==='undefined'?'clean':'dirty');"), "{}", 720));
                break;
            case "result":
                for (String result : new String[]{"42", "({})"}) error("INVALID_RESULT", script("return " + result + ";"), 100);
                equal("", evaluate(script("return '';"), "{}", 720));
                equal("x".repeat(262144), evaluate(script("return 'x'.repeat(262144);"), "{}", 720));
                error("OUTPUT_LIMIT", script("return 'x'.repeat(262145);"), 500);
                break;
            case "boundaries":
                byte[] source = utf8(script("return String(h);") + " ".repeat(131072-script("return String(h);").length()));
                equal("144", NativeQuickJs.INSTANCE.evaluate(source, new byte[65536], UUID, 144, 50));
                equal("2160", NativeQuickJs.INSTANCE.evaluate(source, new byte[65536], UUID, 2160, 2000));
                break;
            case "fresh":
                equal("ok", evaluate("globalThis.previous=1;" + script("return 'ok';"), "{}", 720));
                for (int i=0;i<50;i++) equal("undefined", evaluate(script("return typeof previous;"), "{}", 720));
                break;
            case "capabilities":
                equal("undefined,undefined,undefined,undefined,undefined,undefined,undefined", evaluate(script("return [typeof std,typeof os,typeof fetch,typeof require,typeof Java,typeof console,typeof XMLHttpRequest].join(',');"), "{}", 720));
                error("ENGINE_ERROR", "import * as os from 'os';", 100);
                break;
            case "reviewed":
                String kotlin = Files.readString(Path.of(args[1]));
                String marker = "private const val SOURCE = \"\"\"";
                int first = kotlin.indexOf(marker) + marker.length();
                int last = kotlin.indexOf("\"\"\"", first);
                if (first < marker.length() || last < first) throw new AssertionError("Reviewed source missing");
                String sourceText = kotlin.substring(first,last);
                String base = "https://video.blender.org/object-storage/web_videos/" + ID;
                String fields = ",\"size\":100,\"hasAudio\":true,\"hasVideo\":true}";
                String metadata = "{\"uuid\":\"" + ID + "\",\"isLocal\":true,\"isLive\":false,\"url\":\"https://video.blender.org/videos/watch/" + ID + "\",\"files\":[{\"height\":720,\"resolution\":{\"id\":720},\"fileUrl\":\"" + base + "-720.mp4\"" + fields + ",{\"height\":1080,\"resolution\":{\"id\":1080},\"fileUrl\":\"" + base + "-1080.mp4\"" + fields + "]}";
                for (int h : new int[]{720,1080}) equal("[{\"uuid\":\""+ID+"\",\"height\":"+h+",\"url\":\""+base+"-"+h+".mp4\",\"size\":100}]", evaluate(sourceText,metadata,h));
                equal("[]", evaluate(sourceText,metadata,144));
                equal("[]", evaluate(sourceText,"{}",720));
                break;
            default: throw new AssertionError("Unknown test: " + name);
        }
        System.out.println("PASS " + name);
    }
}

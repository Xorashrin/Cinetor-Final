find_package(Java 17 REQUIRED COMPONENTS Runtime Development)
set(HOST_CLASSES "${CMAKE_CURRENT_BINARY_DIR}/classes")
add_custom_command(
    OUTPUT "${HOST_CLASSES}/com/cinator/app/engines/nuvio/runtime/NativeQuickJsHostTest.class"
    COMMAND "${CMAKE_COMMAND}" -E make_directory "${HOST_CLASSES}"
    COMMAND "${Java_JAVAC_EXECUTABLE}" -Xlint:all -Werror -d "${HOST_CLASSES}"
            "${CMAKE_CURRENT_SOURCE_DIR}/com/cinator/app/engines/nuvio/runtime/NativeQuickJs.java"
            "${CMAKE_CURRENT_SOURCE_DIR}/NativeQuickJsHostTest.java"
    DEPENDS "${CMAKE_CURRENT_SOURCE_DIR}/com/cinator/app/engines/nuvio/runtime/NativeQuickJs.java"
            "${CMAKE_CURRENT_SOURCE_DIR}/NativeQuickJsHostTest.java")
add_custom_target(cinator_quickjs_host_classes ALL
    DEPENDS "${HOST_CLASSES}/com/cinator/app/engines/nuvio/runtime/NativeQuickJsHostTest.class")
add_dependencies(cinator_quickjs_host_classes cinator_quickjs)
set(HOST_JAVA "${Java_JAVA_EXECUTABLE}" -Xcheck:jni
    "-Djava.library.path=$<TARGET_FILE_DIR:cinator_quickjs>" -cp "${HOST_CLASSES}"
    com.cinator.app.engines.nuvio.runtime.NativeQuickJsHostTest)
foreach(CASE invalid arithmetic utf8 loop oom recursion sanitized result boundaries fresh capabilities)
    add_test(NAME "native_quickjs_${CASE}" COMMAND ${HOST_JAVA} "${CASE}")
endforeach()
add_test(NAME native_quickjs_reviewed COMMAND ${HOST_JAVA} reviewed
    "${REPOSITORY_ROOT}/app/src/main/java/com/cinator/app/engines/nuvio/ReviewedEmbeddedScript.kt")
set_tests_properties(native_quickjs_loop native_quickjs_oom PROPERTIES TIMEOUT 5)
set_tests_properties(native_quickjs_invalid native_quickjs_arithmetic native_quickjs_utf8
    native_quickjs_recursion native_quickjs_sanitized native_quickjs_result
    native_quickjs_boundaries native_quickjs_fresh native_quickjs_capabilities
    native_quickjs_reviewed PROPERTIES TIMEOUT 10)

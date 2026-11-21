/* Unity LuaJIT 与同进程 Android 业务之间的 JNI 桥，不创建网络连接。 */
#define _JAVASOFT_JNI_MD_H_
#define JNIEXPORT __attribute__((visibility("default")))
#define JNIIMPORT
#define JNICALL
typedef int jint;
typedef long long jlong;
typedef signed char jbyte;
#include <jni.h>

static JavaVM *offline_vm;
static jclass offline_class;
static jmethodID offline_exchange;
static jbyteArray previous_result;
static const char *previous_text;
static jint previous_size;
static jclass string_class;
static jmethodID string_constructor;
static jmethodID string_bytes;
static jstring utf8_name;

static const char *failure(const char *text) {
    previous_size = 0;
    while (text[previous_size]) previous_size++;
    return text;
}

JNIEXPORT jint pokemon_exchange_size(void) { return previous_size; }

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void) reserved;
    JNIEnv *env = 0;
    if ((*vm)->GetEnv(vm, (void **) &env, JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
    jclass local = (*env)->FindClass(env, "com/qk/local/OfflineBridge");
    if (!local) return JNI_ERR;
    offline_class = (*env)->NewGlobalRef(env, local);
    (*env)->DeleteLocalRef(env, local);
    if (!offline_class) return JNI_ERR;
    offline_exchange = (*env)->GetStaticMethodID(env, offline_class, "exchange", "(Ljava/lang/String;)Ljava/lang/String;");
    if (!offline_exchange) return JNI_ERR;
    local = (*env)->FindClass(env, "java/lang/String");
    if (!local) return JNI_ERR;
    string_class = (*env)->NewGlobalRef(env, local);
    (*env)->DeleteLocalRef(env, local);
    if (!string_class) return JNI_ERR;
    string_constructor = (*env)->GetMethodID(env, string_class, "<init>", "([BLjava/lang/String;)V");
    string_bytes = (*env)->GetMethodID(env, string_class, "getBytes", "(Ljava/lang/String;)[B");
    jstring charset = (*env)->NewStringUTF(env, "UTF-8");
    if (!charset || !string_constructor || !string_bytes) return JNI_ERR;
    utf8_name = (*env)->NewGlobalRef(env, charset);
    (*env)->DeleteLocalRef(env, charset);
    if (!utf8_name) return JNI_ERR;
    offline_vm = vm;
    return JNI_VERSION_1_6;
}

/* 返回值在同一 Unity Lua 线程的下一次调用前有效，调用方须立即复制为 Lua 字符串。 */
JNIEXPORT const char *pokemon_exchange(const char *request) {
    JNIEnv *env = 0;
    if (!offline_vm || !offline_exchange || !request) return failure("{\"ok\":false,\"error\":\"JNI bridge unavailable\"}");
    jint status = (*offline_vm)->GetEnv(offline_vm, (void **) &env, JNI_VERSION_1_6);
    if (status == JNI_EDETACHED) {
        if ((*offline_vm)->AttachCurrentThread(offline_vm, (void **) &env, 0) != JNI_OK)
            return failure("{\"ok\":false,\"error\":\"JNI thread unavailable\"}");
    } else if (status != JNI_OK) {
        return failure("{\"ok\":false,\"error\":\"JNI environment unavailable\"}");
    }
    if (previous_result) {
        (*env)->ReleaseByteArrayElements(env, previous_result, (jbyte *) previous_text, JNI_ABORT);
        (*env)->DeleteGlobalRef(env, previous_result);
        previous_result = 0;
        previous_text = 0;
    }
    jsize size = 0;
    while (request[size]) size++;
    jbyteArray input_bytes = (*env)->NewByteArray(env, size);
    if (input_bytes) (*env)->SetByteArrayRegion(env, input_bytes, 0, size, (const jbyte *) request);
    jstring input = input_bytes ? (*env)->NewObject(env, string_class, string_constructor, input_bytes, utf8_name) : 0;
    if (input_bytes) (*env)->DeleteLocalRef(env, input_bytes);
    if (!input) {
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
        return failure("{\"ok\":false,\"error\":\"JNI request allocation failed\"}");
    }
    jstring output = (jstring) (*env)->CallStaticObjectMethod(env, offline_class, offline_exchange, input);
    (*env)->DeleteLocalRef(env, input);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        if (output) (*env)->DeleteLocalRef(env, output);
        return failure("{\"ok\":false,\"error\":\"App exchange failed\"}");
    }
    if (!output) return failure("{\"ok\":false,\"error\":\"App returned no reply\"}");
    jbyteArray output_bytes = (jbyteArray) (*env)->CallObjectMethod(env, output, string_bytes, utf8_name);
    (*env)->DeleteLocalRef(env, output);
    if (output_bytes) {
        previous_result = (*env)->NewGlobalRef(env, output_bytes);
        (*env)->DeleteLocalRef(env, output_bytes);
    }
    if (previous_result) {
        previous_size = (*env)->GetArrayLength(env, previous_result);
        previous_text = (const char *) (*env)->GetByteArrayElements(env, previous_result, 0);
    }
    if (!previous_text) {
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
        if (previous_result) (*env)->DeleteGlobalRef(env, previous_result);
        previous_result = 0;
        return failure("{\"ok\":false,\"error\":\"JNI response allocation failed\"}");
    }
    return previous_text;
}

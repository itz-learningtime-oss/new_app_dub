#include <jni.h>
#include <sys/auxv.h>
#include <asm/hwcap.h>
extern "C" JNIEXPORT jboolean JNICALL Java_com_voxstudio_app_CpuCapabilities_supportsFast(JNIEnv*, jobject) {
    const unsigned long flags = getauxval(AT_HWCAP);
    const unsigned long required = HWCAP_ASIMDDP | HWCAP_FPHP | HWCAP_ASIMDHP;
    return (flags & required) == required;
}

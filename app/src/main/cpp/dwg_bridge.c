/*
 * DWG -> DXF 桥接层。
 *
 * App 里已经有了一条成熟、测过的 DXF 渲染管线（DxfReader + CadCanvasView），
 * 所以这里不复刻 LibreDWG 的对象模型到 Java，而是让 LibreDWG 把 DWG 拆成
 * DXF 文本，再交回给那条管线。好处是：几何、块、标注、文字都沿用同一套
 * 已经验证过的代码，DWG 和 DXF 走完全相同的渲染路径。
 */
#include <android/log.h>
#include <jni.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include "dwg.h"
#include "bits.h"
#include "out_dxf.h"

#define TAG "CadLens/DwgBridge"

/* 返回给 Kotlin 的错误码，和 strings 里的提示一一对应 */
enum
{
  BRIDGE_OK = 0,
  BRIDGE_ERR_READ = 1,   /* 文件读不进去 / 版本不支持 */
  BRIDGE_ERR_DECODE = 2, /* 解码严重失败（结构损坏） */
  BRIDGE_ERR_WRITE = 3,  /* DXF 写不出来 */
  BRIDGE_ERR_IO = 4      /* 临时文件读写失败 */
};

static jbyteArray read_all (JNIEnv *env, const char *path, long *out_len);

static void
native_log (const char *msg)
{
  /* Android 上直接写 logcat，比 stdout 可靠 */
  __android_log_write (ANDROID_LOG_INFO, TAG, msg);
}

JNIEXPORT jbyteArray JNICALL
Java_dev_cadlens_io_DwgBridge_nativeToDxf (JNIEnv *env, jclass clazz,
                                           jbyteArray src, jstring jdir,
                                           jintArray jstatus)
{
  const char *dir = (*env)->GetStringUTFChars (env, jdir, NULL);
  if (!dir)
    return NULL;

  char in_path[1024];
  char out_path[1024];
  snprintf (in_path, sizeof (in_path), "%s/in_%d.dwg", dir, getpid ());
  snprintf (out_path, sizeof (out_path), "%s/out_%d.dxf", dir, getpid ());

  int status = BRIDGE_ERR_IO;
  jbyteArray result = NULL;
  jsize len = (*env)->GetArrayLength (env, src);
  jbyte *bytes = (*env)->GetByteArrayElements (env, src, NULL);

  do
    {
      FILE *f = fopen (in_path, "wb");
      if (!f)
        break;
      if (len > 0 && fwrite (bytes, 1, (size_t)len, f) != (size_t)len)
        {
          fclose (f);
          break;
        }
      fclose (f);

      Dwg_Data dwg;
      memset (&dwg, 0, sizeof (dwg));
      dwg.opts = 0; /* loglevel 0：别往 stderr 刷日志 */

      int err = dwg_read_file (in_path, &dwg);
      if (err >= DWG_ERR_CRITICAL || dwg.num_objects == 0)
        {
          status = (err >= DWG_ERR_CRITICAL) ? BRIDGE_ERR_DECODE : BRIDGE_ERR_READ;
          char buf[128];
          snprintf (buf, sizeof (buf), "read_file err=0x%x objects=%u", err,
                    dwg.num_objects);
          native_log (buf);
          dwg_free (&dwg);
          break;
        }

      Bit_Chain dat;
      memset (&dat, 0, sizeof (dat));
      dat.version = dwg.header.version;
      dat.from_version = dwg.header.from_version;
      dat.opts = (unsigned char)dwg.opts;
      dat.fh = fopen (out_path, "w");
      if (!dat.fh)
        {
          status = BRIDGE_ERR_WRITE;
          dwg_free (&dwg);
          break;
        }

      err = dwg_write_dxf (&dat, &dwg);
      fclose (dat.fh);
      dwg_free (&dwg);

      if (err >= DWG_ERR_CRITICAL)
        {
          status = BRIDGE_ERR_WRITE;
          break;
        }

      long out_len = 0;
      result = read_all (env, out_path, &out_len);
      status = result ? BRIDGE_OK : BRIDGE_ERR_WRITE;
    }
  while (0);

  /* 临时文件不留在设备上 */
  unlink (in_path);
  unlink (out_path);

  if (bytes)
    (*env)->ReleaseByteArrayElements (env, src, bytes, JNI_ABORT);
  (*env)->ReleaseStringUTFChars (env, jdir, dir);

  if (jstatus)
    {
      jint s = status;
      (*env)->SetIntArrayRegion (env, jstatus, 0, 1, &s);
    }
  return result;
}

/* 一次性把文件读成 byte[]，比逐块读简单，DXF 也不会太大。 */
static jbyteArray
read_all (JNIEnv *env, const char *path, long *out_len)
{
  FILE *f = fopen (path, "rb");
  if (!f)
    return NULL;
  if (fseek (f, 0, SEEK_END) != 0)
    {
      fclose (f);
      return NULL;
    }
  long n = ftell (f);
  if (n <= 0 || n > (long)(256 * 1024 * 1024))
    {
      fclose (f);
      return NULL;
    }
  rewind (f);
  jbyteArray arr = (*env)->NewByteArray (env, (jsize)n);
  if (!arr)
    {
      fclose (f);
      return NULL;
    }
  jbyte *buf = (*env)->GetByteArrayElements (env, arr, NULL);
  long got = (long)fread (buf, 1, (size_t)n, f);
  (*env)->ReleaseByteArrayElements (env, arr, buf, 0);
  fclose (f);
  if (got != n)
    {
      return NULL;
    }
  *out_len = n;
  return arr;
}

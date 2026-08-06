package com.mardous.booming.data.local

/**
 * All calls into libtaglib.so are serialized through this lock.
 *
 * Every native call (getPictures / getMetadata / getAudioProperties /
 * savePictures / savePropertyMap) readlinks `/proc/self/fd/N`, fdopens the
 * descriptor and parses the whole audio file. The stats ranking fires many
 * cover fetches at once and metadata reads also happen during playback, so
 * concurrent calls into the JNI library are common in optimized builds. The
 * lock rules out any race inside the native code; each call takes
 * microseconds-to-milliseconds, so serializing has no measurable cost.
 */
object TagLibMutex {
    val lock = Any()
}

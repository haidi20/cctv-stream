package id.warung.monitor.data

import androidx.test.core.app.ApplicationProvider
import id.warung.monitor.core.model.CameraId
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class KeystoreCameraSecretStoreTest {

    private lateinit var store: KeystoreCameraSecretStore

    @Before
    fun setUp() {
        store = KeystoreCameraSecretStore(
            ApplicationProvider.getApplicationContext(),
            JvmCameraSecretCrypto(),
        )
    }

    @Test
    fun a_saved_password_round_trips_from_encrypted_storage() = runTest {
        store.save(CameraId("cam-1"), "s3cret".toCharArray())

        assertEquals("s3cret", String(store.read(CameraId("cam-1"))!!))
        val raw = java.io.File(
            ApplicationProvider.getApplicationContext<android.content.Context>().filesDir,
            "rtsp-secrets/rtsp-secret-cam-1.bin",
        ).readBytes()
        assertFalse(String(raw, Charsets.ISO_8859_1).contains("s3cret"))
    }

    @Test
    fun a_deleted_camera_loses_its_saved_secret() = runTest {
        store.save(CameraId("cam-1"), "s3cret".toCharArray())
        store.delete(CameraId("cam-1"))

        assertNull(store.read(CameraId("cam-1")))
    }

    @Test
    fun erasing_everything_removes_all_camera_secrets() = runTest {
        store.save(CameraId("cam-1"), "s3cret".toCharArray())
        store.save(CameraId("cam-2"), "another".toCharArray())

        store.deleteAll()

        assertNull(store.read(CameraId("cam-1")))
        assertNull(store.read(CameraId("cam-2")))
    }
}

/** JVM AES-GCM stands in for AndroidKeyStore, which Robolectric cannot host. */
private class JvmCameraSecretCrypto : CameraSecretCrypto {
    private val key = javax.crypto.spec.SecretKeySpec("0123456789abcdef0123456789abcdef".toByteArray(), "AES")

    override fun encrypt(plain: ByteArray): ByteArray {
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, key)
        return cipher.iv + cipher.doFinal(plain)
    }

    override fun decrypt(blob: ByteArray): ByteArray {
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            javax.crypto.Cipher.DECRYPT_MODE,
            key,
            javax.crypto.spec.GCMParameterSpec(128, blob.copyOfRange(0, 12)),
        )
        return cipher.doFinal(blob, 12, blob.size - 12)
    }
}

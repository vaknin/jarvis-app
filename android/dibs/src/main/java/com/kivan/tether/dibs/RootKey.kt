package com.kivan.tether.dibs

import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import kotlin.coroutines.resume

// The phone's key for root steps (SPEC.md §5): made once in the phone's security chip, it never
// leaves the phone and signs only after a strong biometric (fingerprint or face), each time. No
// PIN: dibs knows the PIN and can type it over adb, so a PIN must not be able to sign. Enrolling a
// new fingerprint or face ends the key; then it's set up again, and the laptop needs one password
// to trust the new one.

/** Where the phone's key stands. */
sealed interface KeyState {
    /** None made yet (or it's gone). */
    data object None : KeyState

    /** The fingerprints or faces changed since it was made: it can't sign any more. */
    data object Invalidated : KeyState

    /** It can sign: its public key (SubjectPublicKeyInfo DER), and whether it lives in StrongBox. */
    class Ready(val spki: ByteArray, val strongbox: Boolean) : KeyState {
        val fingerprint: String get() = RootMessage.fingerprint(spki)
    }

    /** Something else went wrong, in plain words. */
    data class Broken(val why: String) : KeyState
}

sealed interface SignResult {
    /** The DER signature. */
    class Signed(val der: ByteArray) : SignResult

    /** The user closed the prompt: nothing happens. */
    data object Cancelled : SignResult

    data object Invalidated : SignResult

    data class Failed(val why: String) : SignResult
}

/** The key and the fingerprint prompt, behind an interface so the screen tests use a fake. */
interface RootKey {
    suspend fun state(): KeyState

    /** The key, made now if there is none or it was invalidated. Returns [KeyState.Ready] or why not. */
    suspend fun ensure(context: Context): KeyState

    /** Asks for a fingerprint or face, then signs [message]; [subtitle] and [description] are the prompt's words. */
    suspend fun sign(context: Context, message: ByteArray, subtitle: String, description: String): SignResult
}

/** The key the screens use; the screen tests set a fake. */
object Root {
    @Volatile var key: RootKey = KeystoreRootKey

    /** The laptop learns the phone's public key (`root-key`): dibs keeps it; only the laptop's setup step trusts it. */
    fun sendKey(k: KeyState.Ready) {
        Dibs.tap(
            "root-key", "Sending the phone's key", "send the phone's key", "root-key",
            JSONObject()
                .put("spki", Base64.getEncoder().encodeToString(k.spki))
                .put("strongbox", k.strongbox)
                .put("fingerprint", k.fingerprint),
        )
    }

    /** What a [KeyState.Invalidated] key means for the user, the same words everywhere. */
    const val INVALIDATED = "The fingerprints or faces on this phone changed, so its key for root steps stopped working. " +
        "Set up again; the laptop needs one password to trust the new key."
}

/** The real one: Android Keystore and the platform's BiometricPrompt (minSdk 34). */
object KeystoreRootKey : RootKey {
    const val ALIAS = "dibs-root-v1"
    private const val ALG = "SHA256withECDSA"

    private fun store(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun entry(ks: KeyStore): KeyStore.PrivateKeyEntry? = ks.getEntry(ALIAS, null) as? KeyStore.PrivateKeyEntry

    override suspend fun state(): KeyState = withContext(Dispatchers.IO) { stateNow() }

    private fun stateNow(): KeyState = try {
        val e = entry(store())
        if (e == null) {
            KeyState.None
        } else {
            // An invalidated key fails here; one that only needs the fingerprint doesn't.
            Signature.getInstance(ALG).initSign(e.privateKey)
            KeyState.Ready(e.certificate.publicKey.encoded, strongbox(e.privateKey))
        }
    } catch (_: KeyPermanentlyInvalidatedException) {
        KeyState.Invalidated
    } catch (e: Exception) {
        KeyState.Broken("The phone couldn't read its key (${e.javaClass.simpleName}).")
    }

    private fun strongbox(k: PrivateKey): Boolean = runCatching {
        KeyFactory.getInstance(k.algorithm, "AndroidKeyStore").getKeySpec(k, KeyInfo::class.java).securityLevel ==
            KeyProperties.SECURITY_LEVEL_STRONGBOX
    }.getOrDefault(false)

    override suspend fun ensure(context: Context): KeyState = withContext(Dispatchers.IO) {
        val now = stateNow()
        if (now is KeyState.Ready) return@withContext now
        // A key that couldn't be read just now may work in a moment: never delete it for that, as a
        // new key needs a password at the laptop. Only none at all or an invalidated one is remade.
        if (now is KeyState.Broken) return@withContext now
        val can = context.getSystemService(BiometricManager::class.java)
            .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        when (can) {
            BiometricManager.BIOMETRIC_SUCCESS -> {}
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED ->
                return@withContext KeyState.Broken("Set up a fingerprint or face unlock on the phone first (Settings, Security).")
            else -> return@withContext KeyState.Broken("This phone can't check a fingerprint or face right now.")
        }
        try {
            val ks = store()
            if (ks.containsAlias(ALIAS)) ks.deleteEntry(ALIAS)
            try {
                generate(strongbox = true)
            } catch (_: StrongBoxUnavailableException) {
                generate(strongbox = false)
            }
            stateNow()
        } catch (e: Exception) {
            KeyState.Broken("The phone couldn't make its key (${e.javaClass.simpleName}).")
        }
    }

    private fun generate(strongbox: Boolean) {
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setUserAuthenticationRequired(true)
            // Every use, a strong biometric only: never the PIN (dibs can type it over adb).
            .setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
            .setInvalidatedByBiometricEnrollment(true)
            .setIsStrongBoxBacked(strongbox)
            .build()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply { initialize(spec) }.generateKeyPair()
    }

    override suspend fun sign(context: Context, message: ByteArray, subtitle: String, description: String): SignResult {
        // The signature is ready before the prompt (an invalidated key says so here), and signs only after it.
        val ready: Any = withContext(Dispatchers.IO) {
            try {
                entry(store())?.let { e -> Signature.getInstance(ALG).apply { initSign(e.privateKey) } }
                    ?: SignResult.Failed("This phone has no key for root steps yet.")
            } catch (_: KeyPermanentlyInvalidatedException) {
                SignResult.Invalidated
            } catch (e: Exception) {
                SignResult.Failed("The phone couldn't use its key (${e.javaClass.simpleName}).")
            }
        }
        if (ready is SignResult) return ready
        val sig = ready as Signature
        return suspendCancellableCoroutine { cont ->
            val cancel = CancellationSignal()
            cont.invokeOnCancellation { cancel.cancel() }
            fun done(r: SignResult) {
                if (cont.isActive) cont.resume(r)
            }
            val main = context.mainExecutor
            val prompt = BiometricPrompt.Builder(context)
                .setTitle("Approve the root step")
                .setSubtitle(subtitle)
                .setDescription(description)
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                // Face unlock still wants a tap to confirm: an approval is never a glance.
                .setConfirmationRequired(true)
                .setNegativeButton("Cancel", main) { _, _ -> done(SignResult.Cancelled) }
                .build()
            prompt.authenticate(
                BiometricPrompt.CryptoObject(sig),
                cancel,
                main,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        // Only the signature the prompt hands back, unlocked by this fingerprint or face, ever signs.
                        val s = result.cryptoObject?.signature
                        done(
                            if (s == null) {
                                SignResult.Failed("The fingerprint prompt gave the phone nothing to sign with.")
                            } else {
                                try {
                                    s.update(message)
                                    SignResult.Signed(s.sign())
                                } catch (_: KeyPermanentlyInvalidatedException) {
                                    SignResult.Invalidated
                                } catch (e: Exception) {
                                    SignResult.Failed("The phone couldn't sign (${e.javaClass.simpleName}).")
                                }
                            },
                        )
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        done(
                            when (errorCode) {
                                BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED,
                                BiometricPrompt.BIOMETRIC_ERROR_CANCELED,
                                -> SignResult.Cancelled
                                else -> SignResult.Failed(errString.toString())
                            },
                        )
                    }
                    // onAuthenticationFailed: a finger or face that didn't match; the prompt goes on.
                },
            )
        }
    }
}

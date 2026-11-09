package com.zamateka.smartfollow.share

sealed class DexcomShareException(message: String) : Exception(message) {
    /** Wrong username or password. Retrying will not help and may lock the account. */
    class InvalidCredentials(message: String) : DexcomShareException(message)

    /** Too many failed logins: Dexcom locks the account for a while. */
    class TooManyAttempts(message: String) : DexcomShareException(message)

    /** The session expired; the client logs in again by itself. */
    class SessionExpired(message: String) : DexcomShareException(message)

    /** Any other error reported by the Share server. */
    class ServerError(val code: String?, message: String) : DexcomShareException(message)
}

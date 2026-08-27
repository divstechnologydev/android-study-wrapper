package one.moveo.studycore

import java.util.UUID

/// Client-generated enrollment identity — port of the extension's
/// `generateEnrollmentId` (`"e_" + crypto.randomUUID()`, via iOS
/// `Enrollment.swift`).
object Enrollment {
    /// One id per enrollment. The app mints it when an activation becomes
    /// pending (before the first enroll attempt) so that consent-screen
    /// retries resend the SAME id — never one id per attempt.
    /// `UUID.randomUUID()` is a SecureRandom v4 UUID, same as the extension.
    fun generateId(): String = "e_" + UUID.randomUUID().toString().lowercase()
}

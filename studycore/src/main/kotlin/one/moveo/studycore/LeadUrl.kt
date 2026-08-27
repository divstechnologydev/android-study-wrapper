package one.moveo.studycore

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/// Lead-in / lead-out URL construction — port of the extension's
/// `target-service.buildLeadUrl` (via iOS `LeadURL.swift`).
///
///   - `participantId` is appended only when the study author opted in
///     (`flow.appendParticipantId`: it hands the id to a third-party form
///     tool). A lambda (the iOS `@autoclosure`) so the participant store is
///     not touched otherwise.
///   - `transactionId` (lead-out only — the caller decides) is echoed as
///     `transaction_id`, the same param name the panel provider used on the
///     setup link, so the provider can credit the completion.
///
/// Existing params of the same name are replaced, not duplicated
/// (`URLSearchParams.set` semantics). Absent participant-id opt-in and
/// absent transaction id ⇒ the URL is returned unchanged (not even
/// re-encoded). Malformed input ⇒ null.
object LeadUrl {
    fun build(
        raw: String,
        flow: StudyConfig.Flow,
        participantId: () -> String,
        transactionId: String? = null,
    ): String? {
        val parsed = raw.toHttpUrlOrNull() ?: return null
        if (!flow.appendParticipantId && transactionId == null) return raw
        val builder = parsed.newBuilder()
        if (flow.appendParticipantId) {
            builder
                .removeAllQueryParameters("participantId")
                .addQueryParameter("participantId", participantId())
        }
        if (transactionId != null) {
            builder
                .removeAllQueryParameters(SetupLink.TRANSACTION_ID_PARAM)
                .addQueryParameter(SetupLink.TRANSACTION_ID_PARAM, transactionId)
        }
        return builder.build().toString()
    }
}

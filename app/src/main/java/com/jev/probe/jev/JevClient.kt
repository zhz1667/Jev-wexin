package com.jev.probe.jev

import com.jev.probe.core.Analysis
import com.jev.probe.core.ChatSnapshot
import com.jev.probe.core.Prefs
import com.jev.probe.core.RankedReply
import com.jev.probe.core.kb.ChatContext

data class ReplyAttempt(
    val replies: List<RankedReply>,
    val ranked: Boolean,
    val warning: String? = null
)

/**
 * Thin facade over the three split clients so callers keep one entry point.
 * Construct with [Prefs] — every route reads its own address / key / model from
 * there, so switching providers in settings takes effect on the next call.
 */
class JevClient(prefs: Prefs) {

    private val judgeClient = JudgeClient(prefs)
    private val replyClient = ReplyClient(prefs)

    /** The 7 judgment questions. Errors come back inside [Analysis.error]. */
    fun judge(snapshot: ChatSnapshot, relationship: String, ctx: ChatContext? = null): Analysis =
        judgeClient.judge(snapshot, relationship, ctx)

    /**
     * Draft 3 candidates on the reply route. When [rank] is true, ask Jev to
     * rank them; if ranking fails, return the unranked draft with a warning.
     */
    fun draftReplies(
        snapshot: ChatSnapshot,
        relationship: String,
        ctx: ChatContext? = null,
        rank: Boolean
    ): ReplyAttempt {
        val candidates = replyClient.draft(snapshot, relationship, ctx)
        if (!rank) {
            return ReplyAttempt(
                candidates.map { RankedReply(it, 0.0, ranked = false) },
                ranked = false,
                warning = "未配置 Jev 判断，候选未排序"
            )
        }
        return try {
            ReplyAttempt(judgeClient.rank(snapshot, relationship, candidates, ctx), ranked = true)
        } catch (e: Exception) {
            ReplyAttempt(
                candidates.map { RankedReply(it, 0.0, ranked = false) },
                ranked = false,
                warning = "Jev 排序失败，已返回未排序候选：${e.message ?: e.javaClass.simpleName}"
            )
        }
    }

    /** Draft 3 candidates on the reply route, then rank them on the judge route. */
    fun draftAndRank(
        snapshot: ChatSnapshot,
        relationship: String,
        ctx: ChatContext? = null
    ): List<RankedReply> = draftReplies(snapshot, relationship, ctx, rank = true).replies

    /** Judge + replies, sequential. Used by the settings connectivity test. */
    fun analyze(snapshot: ChatSnapshot, relationship: String, ctx: ChatContext? = null): Analysis {
        val a = judge(snapshot, relationship, ctx)
        if (a.error != null) return a
        val ranked = try { draftAndRank(snapshot, relationship, ctx) } catch (e: Exception) { emptyList() }
        return a.copy(rankedReplies = ranked)
    }
}

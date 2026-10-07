package com.runner.academy.ui.records

import android.content.Context
import com.runner.academy.R
import com.runner.academy.data.RecordCard
import com.runner.academy.data.RecordStatus
import com.runner.academy.util.FormatUtils

/**
 * The texts of the record card on a workout's details. Just saved, a faster run is a
 * congratulation and a first result on a distance is a calm "First result"; opened later, the
 * card names the records the workout holds or held.
 */
object RecordCardText {

    enum class Tone {
        /** A record now: the tertiary container. */
        CONGRATULATION,

        /** First results, records beaten since: the neutral surface variant. */
        NEUTRAL
    }

    data class Content(
        /** The heading line. */
        val title: String,
        /** The lines under it, in order. */
        val lines: List<String>,
        val tone: Tone,
        /** Some time is "≈": the steps note goes under the lines. */
        val stepsNote: Boolean
    )

    fun of(context: Context, card: RecordCard): Content {
        val entries = card.entries
        val news = entries.count { it.status is RecordStatus.New }
        val justSaved = entries.filter { it.status is RecordStatus.New || it.status == RecordStatus.First }
        val badges = badgeLines(context, card)
        val stepsNote = entries.any { it.effort.stepsShare > 0f }

        if (justSaved.isNotEmpty()) {
            val title = when {
                news >= 2 -> R.string.records_congrats_title_plural
                news == 1 -> R.string.records_congrats_title
                else -> R.string.records_first_result_title
            }
            val lines = justSaved.map { entry ->
                val name = context.getString(RecordsText.nameRes(entry.distance))
                val time = RecordsText.time(context, entry.effort)
                when (val status = entry.status) {
                    is RecordStatus.New -> context.getString(
                        R.string.records_congrats_line,
                        name,
                        time,
                        FormatUtils.formatRecordDelta(entry.effort.elapsedMs + status.improvementMs, entry.effort.elapsedMs)
                    )
                    else -> context.getString(R.string.records_first_result_line, name, time)
                }
            }
            val tone = if (news > 0) Tone.CONGRATULATION else Tone.NEUTRAL
            return Content(context.getString(title), lines + badges, tone, stepsNote)
        }

        val tone = if (entries.any { it.status == RecordStatus.Current }) Tone.CONGRATULATION else Tone.NEUTRAL
        return Content(badges.first(), badges.drop(1), tone, stepsNote)
    }

    /** "Personal record: …" for the records still standing, then "Former record: …" per date beaten. */
    private fun badgeLines(context: Context, card: RecordCard): List<String> {
        val current = card.entries.filter { it.status == RecordStatus.Current }
        val former = card.entries.mapNotNull { entry -> (entry.status as? RecordStatus.Former)?.let { entry to it.until } }
        val lines = mutableListOf<String>()
        if (current.isNotEmpty()) {
            lines += context.getString(R.string.records_badge_current, pairs(context, current))
        }
        former.groupBy({ FormatUtils.formatDate(it.second) }, { it.first }).forEach { (until, beaten) ->
            lines += context.getString(R.string.records_badge_former, pairs(context, beaten), until)
        }
        return lines
    }

    /** "5 km — 24:31, 1 km — 3:58". */
    private fun pairs(context: Context, entries: List<RecordCard.Entry>): String =
        entries.joinToString(", ") { entry ->
            context.getString(
                R.string.records_pair_format,
                context.getString(RecordsText.nameRes(entry.distance)),
                RecordsText.time(context, entry.effort)
            )
        }
}

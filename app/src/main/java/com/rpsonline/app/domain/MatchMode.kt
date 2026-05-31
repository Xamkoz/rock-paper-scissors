package com.rpsonline.app.domain

/**
 * Series format for ranked matches. Values are generated from [shared/game-rules.json].
 */
enum class MatchMode(
    val winsToFinish: Int,
    val bestOfRounds: Int,
    val tiedSeriesScore: Int? = null,
) {
    BO3(
        winsToFinish = GeneratedGameRules.Mode.BO3.winsToFinish,
        bestOfRounds = GeneratedGameRules.Mode.BO3.bestOfRounds,
    ),
    BO5(
        winsToFinish = GeneratedGameRules.Mode.BO5.winsToFinish,
        bestOfRounds = GeneratedGameRules.Mode.BO5.bestOfRounds,
    ),
    BO10(
        winsToFinish = GeneratedGameRules.Mode.BO10.winsToFinish,
        bestOfRounds = GeneratedGameRules.Mode.BO10.bestOfRounds,
        tiedSeriesScore = GeneratedGameRules.Mode.BO10.tiedSeriesScore,
    ),
    ;

    val label: String get() = "Best of $bestOfRounds"

    companion object {
        const val MIN_SELECTION_COUNT = 2

        val DEFAULT_SELECTION: Set<MatchMode> = entries.toSet()

        fun fromString(value: String?): MatchMode =
            entries.find { it.name.equals(value, ignoreCase = true) } ?: BO3

        fun parseStoredNames(names: Set<String>?): Set<MatchMode> {
            if (names.isNullOrEmpty()) return DEFAULT_SELECTION
            return normalizeSelection(
                names.mapNotNull { name -> entries.find { it.name.equals(name, ignoreCase = true) } }
                    .toSet(),
            ).ifEmpty { DEFAULT_SELECTION }
        }

        fun parseRouteArg(value: String?): Set<MatchMode> {
            if (value.isNullOrBlank()) return DEFAULT_SELECTION
            return normalizeSelection(
                value.split(",")
                    .mapNotNull { part -> entries.find { it.name.equals(part.trim(), ignoreCase = true) } }
                    .toSet(),
            ).ifEmpty { DEFAULT_SELECTION }
        }

        fun encodeRouteArg(modes: Set<MatchMode>): String =
            modes.sortedBy { it.ordinal }.joinToString(",") { it.name }

        fun toggleInSelection(current: Set<MatchMode>, mode: MatchMode): Set<MatchMode> {
            if (mode !in current) return normalizeSelection(current + mode)
            val afterRemoval = current - mode
            if (afterRemoval.size >= MIN_SELECTION_COUNT) return afterRemoval
            val needed = MIN_SELECTION_COUNT - afterRemoval.size
            val toAdd = entries.filter { it !in afterRemoval && it != mode }.take(needed)
            return afterRemoval + toAdd
        }

        /** Ensures persisted or toggled selections always include at least [MIN_SELECTION_COUNT] modes. */
        fun normalizeSelection(selection: Set<MatchMode>): Set<MatchMode> {
            if (selection.size >= MIN_SELECTION_COUNT) return selection
            if (selection.isEmpty()) return DEFAULT_SELECTION
            val result = selection.toMutableSet()
            for (candidate in entries) {
                if (result.size >= MIN_SELECTION_COUNT) break
                result += candidate
            }
            return result
        }
    }
}

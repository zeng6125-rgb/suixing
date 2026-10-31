package com.suixing.agent.data

/** The run id every message written before branching existed shares. */
const val BASE_RUN = ""

/**
 * The flat transcript, read as turns.
 *
 * A *turn* is one question and every answer produced for it. A *run* is one of
 * those answers: everything a single engine pass emitted, tool calls included.
 * The list on disk keeps all of them in order, so switching branches is a change
 * of which run is drawn, never a rewrite of the transcript.
 */
object Transcript {

    /** message id -> id of the user message that opened its turn ("" if none yet). */
    fun turnOf(messages: List<StoredMessage>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        var current = ""
        messages.forEach { message ->
            if (message.role == Roles.USER && !message.steer) current = message.id
            out[message.id] = current
        }
        return out
    }

    fun runOf(message: StoredMessage): String = message.runId ?: BASE_RUN

    /** turn id -> the runs that answered it, oldest first. */
    fun runsByTurn(
        messages: List<StoredMessage>,
        turnOf: Map<String, String> = turnOf(messages),
    ): Map<String, List<String>> {
        val out = LinkedHashMap<String, MutableList<String>>()
        messages.forEach { message ->
            if (message.role == Roles.USER) return@forEach
            val turn = turnOf[message.id].orEmpty()
            if (turn.isEmpty()) return@forEach
            val list = out.getOrPut(turn) { mutableListOf() }
            val run = runOf(message)
            if (run !in list) list += run
        }
        return out
    }

    /**
     * The run each turn should draw: the user's pick when it still exists, else the newest.
     *
     * "Newest" skips [BASE_RUN] when the turn has a keyed run at all, and that skip is the point.
     * The empty id is what every message written before branching shares, so one run-less message
     * — an old tool result, a round restored from a draft — used to make the empty run the turn's
     * newest, and the whole turn was then filtered down to that single message: the answer
     * disappeared instead of being drawn beside it. An explicit pick is still honoured, including
     * a pick of [BASE_RUN] itself; only this tie-break prefers a run that names itself.
     */
    fun chosenRuns(
        messages: List<StoredMessage>,
        activeRuns: Map<String, String>,
        /** 调用方已经算过的 `turnOf`：这一层每次重算都是 O(条数)，而组行时会被连着调好几次。 */
        turnOf: Map<String, String> = turnOf(messages),
        pendingRun: String? = null,
    ): Map<String, String> = chosenFrom(runsByTurn(messages, turnOf), activeRuns, pendingRun)

    /**
     * 与 [chosenRuns] 同一套选择规则，但**复用已经算好的 [runs]**。
     *
     * `groupRows` 本来就会先算 `runsByTurn` 再调 `chosenRuns`，而后者内部又算了一遍
     * —— 每落盘一条消息就白付一趟全量扫描 + 一张 `LinkedHashMap<String, MutableList>`。
     * 选择规则逐字照抄（active 命中优先，否则最后一个非空 run，再否则最后一个）。
     */
    fun chosenFrom(
        runs: Map<String, List<String>>,
        activeRuns: Map<String, String>,
        /**
         * 正在生成、**还没有落盘**的那个 run（即 `ChatUiState.liveRunId`）。
         *
         * 没有它的时候，「重新回答」按下后画面不会清空：`activeRuns` 已指向新 runId，
         * 但那个 run 还没有消息 ⇒ `runsByTurn` 里没有它 ⇒ 原来会**回退到最后一条旧的
         * 已落盘回答**，旧答案因此一直挂在屏幕上（用户：「按下的时候就应该把已经输出的一轮隐藏」）。
         *
         * 只在 `activeRuns` 指向的 runId **正好等于**它时才采用；其它「不在列表里」的值
         * （例如生成失败后残留的无效 id）仍然回退 —— 否则那一轮的回答会永久消失。
         */
        pendingRun: String? = null,
    ): Map<String, String> {
        return runs.mapValues { (turn, list) ->
            val wanted = activeRuns[turn]
            when {
                wanted == null -> list.lastOrNull { it.isNotEmpty() } ?: list.last()
                wanted in list -> wanted
                // 新 run 尚未落盘，且它就是当前正在生成的那个 ⇒ 采用它（该轮暂时没有消息，
                // 画面因此清空，新内容由 live 行渲染）。
                pendingRun != null && wanted == pendingRun -> wanted
                // 其余情况（无效/过期 id）：回退，避免整轮消失。
                else -> list.lastOrNull { it.isNotEmpty() } ?: list.last()
            }
        }
    }

    /**
     * Drops every candidate answer the user is not looking at. The full list stays
     * on disk; this is what the screen draws and what gets replayed to the model.
     */
    fun active(
        messages: List<StoredMessage>,
        activeRuns: Map<String, String>,
        /** 同上：组行时调用方已经算过，别再重算一遍。 */
        turnOf: Map<String, String> = turnOf(messages),
        /**
         * 调用方用 [chosenFrom] 已经算好的「每轮画哪个 run」。
         *
         * 传进来就不再走 [chosenRuns] —— 它内部要重扫一遍 [runsByTurn]，而组行时那份
         * `runs` 本来就摆在调用方手里。不传则与不带这个参数时逐字同一结果。
         */
        chosen: Map<String, String>? = null,
        /** 正在生成、尚未落盘的 run；见 [chosenFrom] 的说明。 */
        pendingRun: String? = null,
    ): List<StoredMessage> {
        val perTurn = chosen ?: chosenRuns(messages, activeRuns, turnOf, pendingRun)
        return messages.filter { message ->
            if (message.role == Roles.USER) return@filter true
            val turn = turnOf[message.id].orEmpty()
            // A transcript that starts mid-turn has no question to hang off; keep it.
            if (turn.isEmpty()) return@filter true
            runOf(message) == perTurn[turn]
        }
    }

    /**
     * The prompt a re-answer needs: every earlier turn plus the question itself,
     * with the answer being replaced left out. Feed [active] through this first.
     */
    fun withoutLastAnswer(messages: List<StoredMessage>): List<StoredMessage> {
        val lastTurn = messages.lastOrNull { it.role == Roles.USER && !it.steer }?.id ?: return messages
        val turnOf = turnOf(messages)
        return messages.filter { it.role == Roles.USER || turnOf[it.id] != lastTurn }
    }
}

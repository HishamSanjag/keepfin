package app.keepfin.ui.model

/** The two ways a flow tells the person something (a snackbar, with up to two buttons); every flow's host brings them. */
internal interface Notices {
    fun say(text: String, actionLabel: String? = null, action: (() -> Unit)? = null)
    /** A notice with two buttons (Play and Show in Library). Its own name, so a trailing block after [say] never lands on a second button nobody sees. */
    fun sayWithTwo(text: String, actionLabel: String?, action: (() -> Unit)?, secondLabel: String, secondAction: () -> Unit)
}

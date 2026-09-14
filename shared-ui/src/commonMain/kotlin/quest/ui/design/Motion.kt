package quest.ui.design

/**
 * Global switch for looping animations (Pip's bounce, island glow). Off under UI tests — XCUITest waits for the app
 * to become idle before every event, and an endless animation never lets it — and available for a "reduce motion" setting.
 */
object Motion {
    var reduced: Boolean = false
}

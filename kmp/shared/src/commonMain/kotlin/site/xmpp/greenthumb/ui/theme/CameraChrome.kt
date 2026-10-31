package site.xmpp.greenthumb.ui.theme

/**
 * Colours of the in-app camera chrome (M11 item 1). The viewfinder is dark in both themes, like the
 * system camera, so its controls use fixed light-on-dark colours instead of the «Лес» palette.
 */
object CameraChrome {
    /** Behind the live preview and the letterbox. */
    val background = rgb(0x000000)

    /** Icons, labels and the shutter on top of the preview. */
    val content = rgb(0xFFFFFF)

    /** Dark text and marks on a [content] fill (the selected mode, the shutter mark). */
    val onContent = rgb(0x0A100B)

    /** Plate under a control or a hint, so it stays readable over a bright scene. */
    val scrim = rgb(0x000000).copy(alpha = 0.55f)

    /** Corners of the aiming frame. */
    val frame = rgb(0xFFFFFF).copy(alpha = 0.85f)

    /** Opacity of [background] over the preview at the moment of the shutter tap… */
    const val shotBlink = 0.85f

    /** …and while the photo is being taken, so the scene stays visible under it. */
    const val shotVeil = 0.4f
}

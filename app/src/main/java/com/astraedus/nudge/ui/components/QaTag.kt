package com.astraedus.nudge.ui.components

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId

/**
 * Marks a control for the `:qa-driver` device tests: [tag] (one of
 * [com.astraedus.nudge.ui.testtags.UiTags]) becomes a resource id UiAutomator can find with
 * `By.res(tag)`. Invisible to the user and to TalkBack; it only adds a view-id to the a11y node.
 *
 * `testTagsAsResourceId` is experimental Compose UI, so the opt-in lives here once rather than at
 * every tagged call site.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.qaTag(tag: String): Modifier =
    semantics { testTagsAsResourceId = true }.testTag(tag)

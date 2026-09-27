package com.example.forgegen.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.forgegen.ForgeTagManager
import com.example.forgegen.PromptTypingRules
import com.example.forgegen.Suggestion
import com.example.forgegen.Suggestions
import com.example.forgegen.TagInsertRules
import com.example.forgegen.TagList
import com.example.forgegen.TypedFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The prompt field being typed in, for the tag suggestions (2.4.2): its text and caret, and how to change them. The
 * main screen holds one; a prompt field reports itself while it has the focus.
 */
@Stable
class PromptTyping {
    var value: TextFieldValue? by mutableStateOf(null)
        private set
    private var owner: Any? = null
    private var apply: ((TextFieldValue) -> Unit)? = null

    fun focused(
        owner: Any,
        value: TextFieldValue,
        apply: (TextFieldValue) -> Unit,
    ) {
        this.owner = owner
        this.apply = apply
        this.value = value
    }

    fun changed(
        owner: Any,
        value: TextFieldValue,
    ) {
        if (this.owner === owner) this.value = value
    }

    fun left(owner: Any) {
        if (this.owner !== owner) return
        this.owner = null
        apply = null
        value = null
    }

    fun replace(edited: TextFieldValue) {
        apply?.invoke(edited)
    }
}

/** The main screen's [PromptTyping]; null elsewhere (no suggestions there). */
val LocalPromptTyping = staticCompositionLocalOf<PromptTyping?> { null }

/** The chips computed for a fragment. */
private class Shown(
    val fragment: TypedFragment?,
    val suggestions: List<Suggestion>,
)

/**
 * The strip docked on the keyboard: chips for the tag being typed (a category dot, the name and the Danbooru post
 * count), wildcards after `__` and LoRAs after `<lora:`. A tap puts the chip in place of what was typed, followed by
 * ", ". In the one-bar layout ([oneBar], no room for the field) its left part shows the end of the text at the caret.
 */
@Composable
fun TagSuggestionStrip(
    typing: PromptTyping,
    tags: TagList?,
    rules: TagInsertRules,
    status: ForgeTagManager.Status,
    wildcards: List<String>,
    loras: List<String>,
    oneBar: Boolean,
    height: Dp,
    modifier: Modifier = Modifier,
) {
    val value = typing.value ?: return
    val caret = if (value.selection.collapsed) value.selection.start else -1
    val fragment = remember(value.text, caret) { if (caret < 0) null else PromptTypingRules.fragmentAt(value.text, caret) }
    val shown by produceState(Shown(null, emptyList()), fragment, tags, rules, wildcards, loras) {
        this.value =
            withContext(Dispatchers.Default) {
                Shown(fragment, Suggestions.forFragment(fragment, tags, rules, wildcards, loras))
            }
    }
    val listState = rememberLazyListState()
    LaunchedEffect(shown) { listState.scrollToItem(0) }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        // Taps between the chips must not reach the screen behind, which would close the keyboard.
        modifier = modifier.fillMaxWidth().height(height).pointerInput(Unit) { detectTapGestures { } },
    ) {
        Column {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxSize()) {
                if (oneBar) {
                    TextAtCaret(value.text.substring(0, caret.coerceIn(0, value.text.length)), Modifier.fillMaxWidth(0.38f))
                    VerticalDivider(modifier = Modifier.fillMaxHeight(), color = MaterialTheme.colorScheme.outlineVariant)
                }
                // The last chips stay until the new ones are ready (a few ms), so they do not blink at every key.
                val chips = if (fragment == null) emptyList() else shown.suggestions
                if (chips.isEmpty()) {
                    Text(
                        hint(fragment?.let { shown.fragment }, tags, status),
                        fontSize = 12.sp,
                        maxLines = 1,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                } else {
                    val chipHeight = (height - 12.dp).coerceIn(24.dp, 32.dp)
                    LazyRow(
                        state = listState,
                        contentPadding = PaddingValues(horizontal = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(chips) { suggestion ->
                            SuggestionChip(suggestion, chipHeight) {
                                // In place of what is typed now (a chip may be from one key before).
                                val at = fragment?.takeIf { it.kind == suggestion.kind } ?: return@SuggestionChip
                                val edit = PromptTypingRules.insert(value.text, at, suggestion.insertion)
                                typing.replace(TextFieldValue(edit.text, TextRange(edit.caret)))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SuggestionChip(
    suggestion: Suggestion,
    height: Dp,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier.height(height),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(horizontal = 10.dp),
        ) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(categoryColor(suggestion)))
            if (suggestion.alias != null) {
                Text("${suggestion.alias} →", fontSize = 11.sp, maxLines = 1, color = dimmed())
            }
            Text(suggestion.label, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            if (suggestion.count > 0) {
                Text(
                    Suggestions.compactCount(suggestion.count),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    color = dimmed(),
                )
            }
        }
    }
}

/** The end of the text before the caret, then the caret (the one-bar layout, where the field has no room). */
@Composable
private fun TextAtCaret(
    before: String,
    modifier: Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.End,
        modifier = modifier.fillMaxHeight().padding(horizontal = 8.dp),
    ) {
        Box(Modifier.weight(1f, fill = false).clipToBounds(), contentAlignment = Alignment.CenterEnd) {
            // Laid out at its full width and aligned to the end, so the start is cut off, not the caret's side.
            Text(
                before.takeLast(TEXT_AT_CARET_CHARS),
                fontSize = 13.sp,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.wrapContentWidth(Alignment.End, unbounded = true),
            )
        }
        Box(
            Modifier
                .padding(start = 1.dp)
                .width(2.dp)
                .height(17.dp)
                .background(MaterialTheme.colorScheme.primary),
        )
    }
}

private const val TEXT_AT_CARET_CHARS = 80

@Composable
private fun dimmed() = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.65f)

/** What the strip says when it has no chips. */
private fun hint(
    fragment: TypedFragment?,
    tags: TagList?,
    status: ForgeTagManager.Status,
): String =
    when (fragment?.kind) {
        null -> "Type a tag · __ for wildcards · <lora: for LoRAs"
        TypedFragment.Kind.WILDCARD -> "No matching wildcards"
        TypedFragment.Kind.LORA -> "No matching LoRAs"
        TypedFragment.Kind.TAG ->
            when {
                tags != null -> "No matching tags"
                status.source == ForgeTagManager.Source.LOADING -> "Loading the server's tag list…"
                status.source == ForgeTagManager.Source.MISSING -> "No tag list: the server has no tagcomplete extension"
                else -> "No tag list yet: it comes from the server's tagcomplete extension"
            }
    }

/** The dot of a chip: the Danbooru category of a tag (general, artist, series, character, meta), or wildcard/LoRA. */
private fun categoryColor(suggestion: Suggestion): Color =
    when (suggestion.kind) {
        TypedFragment.Kind.WILDCARD -> Color(0xFF14B8A6)
        TypedFragment.Kind.LORA -> Color(0xFFF59E0B)
        TypedFragment.Kind.TAG ->
            when (suggestion.category) {
                0 -> Color(0xFF2F7FF5)
                1 -> Color(0xFFE5484D)
                3 -> Color(0xFFB86BFF)
                4 -> Color(0xFF30B46C)
                5 -> Color(0xFFF59E0B)
                else -> Color(0xFF9AA3B6)
            }
    }

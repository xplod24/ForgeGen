
package com.example.forgegen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController

/* ============================================================================

 * POMOCNICZA KLASA DLA STANU ROBOCZEGO (DRAFT)

 * Używamy SnapshotStateList aby Compose wykrywało dodawanie/usuwanie pojedynczych słów

 * ============================================================================ */

data class WildcardDraftWrapper(
    val name: String,
    val words: androidx.compose.runtime.snapshots.SnapshotStateList<String>,
    var isExpanded: MutableState<Boolean>,
)

/* ============================================================================

 * EKRAN ZARZĄDZANIA WILDCARDAMI

 * ============================================================================ */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WildcardsScreen(
    viewModel: ForgeViewModel,
    navController: NavHostController,
) {
    val dbWildcards by viewModel.wildcards.collectAsStateWithLifecycle()

    // Lista robocza - modyfikacje zachodzą tylko tutaj, aż do wciśnięcia "Zapisz"

    val draftList = remember { mutableStateListOf<WildcardDraftWrapper>() }

    var isInitialized by remember { mutableStateOf(false) }

    // Wczytanie danych z bazy do stanu roboczego (tylko raz przy wejściu)

    LaunchedEffect(dbWildcards) {
        if (!isInitialized) {
            draftList.clear()

            dbWildcards.forEach { wc ->

                val wordsList =
                    wc.content
                        .split("\n", ",")
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }

                val stateWords = mutableStateListOf(*wordsList.toTypedArray())

                draftList.add(WildcardDraftWrapper(wc.name, stateWords, mutableStateOf(false)))
            }

            isInitialized = true
        }
    }

    var showNewKeyDialog by remember { mutableStateOf(false) }

    var newKeyName by remember { mutableStateOf("") }

    val onSave: () -> Unit = {
        // 1. Wykryj usunięte kategorie i usuń je z bazy

        val draftNames = draftList.map { it.name }.toSet()

        dbWildcards.forEach { dbWc ->

            if (!draftNames.contains(dbWc.name)) {
                viewModel.deleteWildcard(dbWc)
            }
        }

        // 2. Zapisz obecne kategorie

        draftList.forEach { draft ->

            if (draft.name.isNotBlank()) {
                val content = draft.words.joinToString("\n")

                viewModel.saveWildcard(draft.name, content)
            }
        }

        viewModel.showToast("Wildcards saved successfully")

        navController.popBackStack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Wildcards", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(onClick = onSave) {
                        // Ikona dyskietki (zapisz)

                        Icon(Icons.Default.Save, "Save to disk")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            )
        },
    ) { padding ->

        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            // Główny przycisk dodawania

            Button(
                onClick = { showNewKeyDialog = true },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(12.dp),
            ) {
                Icon(Icons.Default.Add, null)

                Spacer(Modifier.width(8.dp))

                Text("Add new wildcard key", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }

            Spacer(Modifier.height(16.dp))

            if (draftList.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No dictionaries. Add a new wildcard key.", color = Color.Gray)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(draftList, key = { it.name }) { draft ->

                        WildcardItemCard(
                            draft = draft,
                            onRemoveKey = {
                                draftList.remove(draft)
                            },
                        )
                    }
                }
            }
        }
    }

    // Okno dialogowe dla nowej kategorii

    if (showNewKeyDialog) {
        AlertDialog(
            onDismissRequest = {
                showNewKeyDialog = false

                newKeyName = ""
            },
            title = { Text("New Wildcard Key") },
            text = {
                Column {
                    Text("Enter category name (without '__'):", fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp))

                    OutlinedTextField(
                        value = newKeyName,
                        onValueChange = { newKeyName = it.replace(Regex("[^a-zA-Z0-9_\\-]"), "") }, // Zabezpieczenie przed spacjami
                        label = { Text("Name, e.g. hair_color") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val cleanName = newKeyName.trim()

                        if (cleanName.isNotBlank() && !draftList.any { it.name == cleanName }) {
                            draftList.add(
                                WildcardDraftWrapper(
                                    name = cleanName,
                                    words = androidx.compose.runtime.mutableStateListOf(),
                                    isExpanded = mutableStateOf(true),
                                ),
                            )

                            newKeyName = ""

                            showNewKeyDialog = false
                        } else if (draftList.any { it.name == cleanName }) {
                            viewModel.showToast("A key with this name already exists!")
                        }
                    },
                ) {
                    Text("Add")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showNewKeyDialog = false

                    newKeyName = ""
                }) { Text("Cancel") }
            },
        )
    }
}

/* ============================================================================

 * KOMPONENT KARTY DANEGO WILDCARDA

 * ============================================================================ */

@Composable
fun WildcardItemCard(
    draft: WildcardDraftWrapper,
    onRemoveKey: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Nagłówek akordeonu

            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable { draft.isExpanded.value = !draft.isExpanded.value }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "__${draft.name}__",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )

                // Usunięcie całego klucza ("-")

                IconButton(onClick = onRemoveKey, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Remove, "Delete Key", tint = MaterialTheme.colorScheme.error)
                }

                Spacer(Modifier.width(8.dp))

                // Strzałka rozwijania

                Icon(
                    imageVector = if (draft.isExpanded.value) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = "Expand/Collapse",
                    tint = Color.Gray,
                )
            }

            // Zawartość po rozwinięciu

            AnimatedVisibility(visible = draft.isExpanded.value) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surface)
                            .padding(16.dp),
                ) {
                    // Lista istniejących słów

                    if (draft.words.isEmpty()) {
                        Text(
                            "No words in this dictionary.",
                            fontSize = 12.sp,
                            color = Color.Gray,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                    } else {
                        draft.words.forEachIndexed { index, word ->

                            Row(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                // Ozdobny punkt (bullet)

                                Box(
                                    modifier =
                                        Modifier
                                            .size(
                                                6.dp,
                                            ).clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
                                )

                                Spacer(modifier = Modifier.width(8.dp))

                                Text(text = word, fontSize = 14.sp, modifier = Modifier.weight(1f))

                                // Usunięcie pojedynczego słowa ("-")

                                IconButton(
                                    onClick = { draft.words.removeAt(index) },
                                    modifier = Modifier.size(24.dp),
                                ) {
                                    Icon(
                                        Icons.Default.Remove,
                                        "Delete word",
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                    Spacer(modifier = Modifier.height(12.dp))

                    // Linia horyzontalna do dodawania nowego słowa

                    var newWordValue by remember { mutableStateOf("") }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = newWordValue,
                            onValueChange = { newWordValue = it },
                            placeholder = { Text("Add new word...", fontSize = 12.sp) },
                            singleLine = true,
                            modifier = Modifier.weight(1f).height(50.dp),
                            textStyle =
                                androidx.compose.ui.text
                                    .TextStyle(fontSize = 14.sp),
                            shape = RoundedCornerShape(8.dp),
                        )

                        Spacer(modifier = Modifier.width(8.dp))

                        // Przycisk dodawania słowa ("+")

                        IconButton(
                            onClick = {
                                val cleanWord = newWordValue.trim()

                                if (cleanWord.isNotBlank() && !draft.words.contains(cleanWord)) {
                                    draft.words.add(cleanWord)
                                }

                                newWordValue = ""
                            },
                            modifier =
                                Modifier
                                    .size(48.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.primaryContainer),
                        ) {
                            Icon(Icons.Default.Add, "Add", tint = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                    }
                }
            }
        }
    }
}

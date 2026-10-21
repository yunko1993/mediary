package app.mediary.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mediary.MediaryViewModel
import app.mediary.core.MedicationPerson

@Composable
internal fun PersonPicker(people: List<MedicationPerson>, selectedId: String?, onSelect: (String?) -> Unit,
    allowAll: Boolean = false, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth().testTag(if (allowAll) "person-filter" else "person-selector")) {
            Text(people.find { it.id == selectedId }?.let { "用药人：${it.name}" } ?: if (allowAll) "全部用药人" else "请选择用药人", modifier = Modifier.weight(1f))
            Icon(Icons.Outlined.ExpandMore, null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (allowAll) DropdownMenuItem(text = { Text("全部用药人") }, onClick = { onSelect(null); expanded = false })
            people.forEach { person ->
                DropdownMenuItem(text = { Text(person.name) }, onClick = { onSelect(person.id); expanded = false }, modifier = Modifier.testTag("person-choice-${person.id}"))
            }
        }
    }
}

@Composable
internal fun PeopleSettings(model: MediaryViewModel) {
    val snapshot by model.snapshot.collectAsStateWithLifecycle()
    val saving by model.personSaving.collectAsStateWithLifecycle()
    val error by model.personError.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<MedicationPerson?>(null) }
    SoftCard {
        Text("用药人", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Text("为自己和家人分别记录，只保存在这部手机。", color = Muted, fontSize = 12.sp)
        snapshot.people.forEach { person ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(person.name, modifier = Modifier.weight(1f))
                TextButton(onClick = { selected = person; model.resetPersonError(); editing = true }) { Text("修改称呼") }
            }
        }
        OutlinedButton(onClick = { selected = null; model.resetPersonError(); editing = true }) { Text("添加用药人") }
    }
    if (editing) PersonNameDialog(selected, saving, error,
        onDismiss = { if (!saving) editing = false },
        onSave = { model.savePerson(selected, it) { editing = false } })
}

@Composable
internal fun PersonNameDialog(person: MedicationPerson?, saving: Boolean, error: String?, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember(person?.id) { mutableStateOf(person?.name.orEmpty()) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (person == null) "添加用药人" else "修改用药人称呼") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(name, { name = it }, enabled = !saving, label = { Text("称呼，例如：我、爸爸、妈妈") }, singleLine = true)
                Text("1–24 字；修改称呼后，历史记录仍保留登记时的称呼。", color = Muted, fontSize = 12.sp)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(enabled = !saving && name.isNotBlank(), onClick = { onSave(name) }) { Text(if (saving) "正在保存…" else "保存用药人") } },
        dismissButton = { TextButton(enabled = !saving, onClick = onDismiss) { Text("取消") } })
}

package com.everyroutes.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.everyroutes.app.model.BlockEntry
import com.everyroutes.app.model.TaskEntry
import com.everyroutes.app.model.buildTodayView
import java.time.LocalDate

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                TodayScreen()
            }
        }
    }
}

@Composable
fun TodayScreen() {
    val view = remember {
        buildTodayView(LocalDate.now(), emptyList(), emptyList())
    }
    Column(Modifier.padding(16.dp)) {
        Text(stringResource(R.string.today_title), style = MaterialTheme.typography.headlineSmall)
        if (view.entries.isEmpty()) {
            Text(stringResource(R.string.empty_today), Modifier.padding(top = 12.dp))
        } else {
            LazyColumn {
                items(view.entries) { entry ->
                    when (entry) {
                        is BlockEntry -> Text("${entry.block.start}–${entry.block.end} ${entry.block.title}")
                        is TaskEntry -> Text("${entry.task.at} ${entry.task.title}")
                    }
                }
            }
        }
    }
}

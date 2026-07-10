package dev.xangma.hidratespark.healthconnect

import android.app.Activity
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class PermissionsRationaleActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val padding = (20 * resources.displayMetrics.density).toInt()
        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(padding, padding, padding, padding)
                addView(
                    TextView(this@PermissionsRationaleActivity).apply {
                        text = getString(R.string.privacy_title)
                        textSize = 24f
                    },
                    layout(),
                )
                addView(
                    TextView(this@PermissionsRationaleActivity).apply {
                        text = getString(R.string.privacy_body)
                        textSize = 16f
                    },
                    layout(),
                )
                addView(
                    Button(this@PermissionsRationaleActivity).apply {
                        text = getString(R.string.close)
                        setOnClickListener { finish() }
                    },
                    layout(),
                )
            },
        )
    }

    private fun layout() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    )
}

package com.runner.academy.ui.workout

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.runner.academy.R
import com.runner.academy.data.ConflictResolution
import com.runner.academy.data.PendingConflict
import com.runner.academy.databinding.ItemImportConflictBinding

/** A card per waiting workout: what it overlaps and the three decisions. */
class ImportConflictsAdapter(
    private val onResolve: (pendingId: Long, ConflictResolution) -> Unit
) : ListAdapter<PendingConflict, ImportConflictsAdapter.Holder>(Diff) {

    /** False while a decision is saved. */
    var enabled: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            notifyItemRangeChanged(0, itemCount)
        }

    class Holder(val binding: ItemImportConflictBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(ItemImportConflictBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val conflict = getItem(position)
        val binding = holder.binding
        val context = binding.root.context
        binding.textViewImportConflict.text = ImportConflictText.message(context, conflict)
        // Nothing overlaps any more (deleted meanwhile): only import it or not
        val overlaps = conflict.others.isNotEmpty()
        binding.buttonImportConflictReplace.visibility = if (overlaps) View.VISIBLE else View.GONE
        binding.buttonImportConflictKeepBoth.setText(
            if (overlaps) R.string.workout_import_conflict_keep_both else R.string.workout_import_conflict_import
        )
        listOf(
            binding.buttonImportConflictSkip to ConflictResolution.KEEP_EXISTING,
            binding.buttonImportConflictReplace to ConflictResolution.REPLACE,
            binding.buttonImportConflictKeepBoth to ConflictResolution.KEEP_BOTH
        ).forEach { (button, resolution) ->
            button.isEnabled = enabled
            button.setOnClickListener { onResolve(conflict.pendingId, resolution) }
        }
    }

    private object Diff : DiffUtil.ItemCallback<PendingConflict>() {
        override fun areItemsTheSame(oldItem: PendingConflict, newItem: PendingConflict) = oldItem.pendingId == newItem.pendingId
        override fun areContentsTheSame(oldItem: PendingConflict, newItem: PendingConflict) = oldItem == newItem
    }
}

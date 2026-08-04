package com.runner.academy.ui.trainingplan

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.runner.academy.R
import com.runner.academy.appContainer
import com.runner.academy.databinding.FragmentPlanListBinding
import com.runner.academy.util.ShareExports
import com.runner.academy.util.TrainingPlanBackupFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

class PlanListFragment : Fragment() {
    private var _binding: FragmentPlanListBinding? = null
    private val binding get() = _binding!!

    private val viewModel: TrainingPlanViewModel by viewModels {
        TrainingPlanViewModelFactory(requireContext().appContainer().trainingPlanRepository)
    }

    private var isSpeedDialOpen = false

    private val openJsonLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) importFromJsonUri(uri)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPlanListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val adapter = PlanListAdapter { plan ->
            findNavController().navigate(
                R.id.nav_plan_edit,
                PlanEditFragmentArgs(planId = plan.id).toBundle()
            )
        }
        binding.recyclerViewPlans.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerViewPlans.adapter = adapter

        binding.buttonOpenTemplates.setOnClickListener {
            findNavController().navigate(R.id.nav_templates)
        }
        binding.fabMain.setOnClickListener {
            setSpeedDialExpanded(!isSpeedDialOpen)
        }
        binding.fabSpeedDialScrim.setOnClickListener {
            setSpeedDialExpanded(false)
        }
        binding.fabAddPlan.setOnClickListener {
            setSpeedDialExpanded(false)
            findNavController().navigate(
                R.id.nav_plan_edit,
                PlanEditFragmentArgs(planId = -1L).toBundle()
            )
        }
        binding.fabImportPlans.setOnClickListener {
            setSpeedDialExpanded(false)
            openJsonLauncher.launch(arrayOf("application/json", "text/*", "*/*"))
        }
        binding.fabExportPlans.setOnClickListener {
            setSpeedDialExpanded(false)
            exportBackup()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.plans.collect { list ->
                adapter.submitList(list)
                binding.textViewEmpty.visibility =
                    if (list.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun setSpeedDialExpanded(expanded: Boolean) {
        if (_binding == null) return
        isSpeedDialOpen = expanded
        val dial = binding.layoutFabSpeedDial
        val scrim = binding.fabSpeedDialScrim
        val mainFab = binding.fabMain

        if (expanded) {
            scrim.visibility = View.VISIBLE
            scrim.alpha = 0f
            scrim.animate().alpha(1f).setDuration(150).start()
            dial.visibility = View.VISIBLE
            dial.alpha = 0f
            dial.translationY = dial.height.takeIf { it > 0 }?.toFloat() ?: 48f
            dial.animate().alpha(1f).translationY(0f).setDuration(180).start()
            mainFab.animate().rotation(45f).setDuration(180).start()
            mainFab.contentDescription = getString(R.string.plans_fab_close)
        } else {
            scrim.animate().alpha(0f).setDuration(150).withEndAction {
                if (_binding != null) scrim.visibility = View.GONE
            }.start()
            dial.animate().alpha(0f).translationY(48f).setDuration(150).withEndAction {
                if (_binding != null) dial.visibility = View.GONE
            }.start()
            mainFab.animate().rotation(0f).setDuration(180).start()
            mainFab.contentDescription = getString(R.string.plans_fab_actions)
        }
    }

    private fun exportBackup() {
        viewLifecycleOwner.lifecycleScope.launch {
            val progress = showProgressDialog(R.string.plans_export_progress)
            try {
                val json = withContext(Dispatchers.IO) {
                    viewModel.exportBackupJson(requireContext().packageName)
                }
                val file = File(
                    requireContext().cacheDir,
                    TrainingPlanBackupFormat.getBackupJsonFileName()
                )
                withContext(Dispatchers.IO) {
                    file.writeText(json, Charsets.UTF_8)
                }
                shareFile(file)
            } catch (e: IllegalStateException) {
                Toast.makeText(requireContext(), R.string.no_data_export, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Plans export failed: ${e.message}", e)
                Toast.makeText(
                    requireContext(),
                    getString(R.string.export_error, e.message ?: ""),
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                progress.dismiss()
            }
        }
    }

    private fun importFromJsonUri(uri: Uri) {
        viewLifecycleOwner.lifecycleScope.launch {
            val progress = showProgressDialog(R.string.plans_import_progress)
            try {
                val json = withContext(Dispatchers.IO) {
                    requireContext().contentResolver.openInputStream(uri)?.use { stream ->
                        BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).readText()
                    } ?: throw IllegalArgumentException("Cannot read file")
                }
                val result = withContext(Dispatchers.IO) {
                    viewModel.importBackupJson(json)
                }
                Toast.makeText(
                    requireContext(),
                    getString(
                        R.string.plans_import_success,
                        result.templatesImported,
                        result.plansImported
                    ),
                    Toast.LENGTH_LONG
                ).show()
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Plans import failed: ${e.message}", e)
                Toast.makeText(
                    requireContext(),
                    getString(R.string.plans_import_error, e.message ?: ""),
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                progress.dismiss()
            }
        }
    }

    private fun shareFile(file: File) {
        ShareExports.shareFile(
            context = requireContext(),
            file = file,
            mimeType = "application/json",
            chooserTitle = getString(R.string.plans_export_title),
            readyMessage = getString(R.string.plans_export_ready)
        )
    }

    private fun showProgressDialog(messageRes: Int) =
        MaterialAlertDialogBuilder(requireContext())
            .setMessage(messageRes)
            .setCancelable(false)
            .create()
            .also { it.show() }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val TAG = "PlanListFragment"
    }
}

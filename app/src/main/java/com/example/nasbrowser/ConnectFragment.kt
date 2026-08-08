package com.example.nasbrowser

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.example.nasbrowser.databinding.FragmentConnectBinding
import com.example.nasbrowser.model.ConnectionParams
import com.example.nasbrowser.util.PrefsStore
import com.example.nasbrowser.viewmodel.BrowserViewModel

class ConnectFragment : Fragment() {
    private var _binding: FragmentConnectBinding? = null
    private val binding get() = _binding!!
    private val vm: BrowserViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentConnectBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Load persisted connection (password only if "remember" was on).
        // Nothing is pre-filled with personal info — on first use the fields are empty and the
        // user must enter their own NAS details, which are then saved on the device.
        val prefs = PrefsStore.load(requireContext())
        binding.etHost.setText(prefs.host)
        binding.etUser.setText(prefs.user)
        binding.etPass.setText(prefs.pass)
        binding.etDomain.setText(prefs.domain)
        binding.cbRemember.isChecked = prefs.remember

        vm.connection.observe(viewLifecycleOwner) { conn ->
            if (conn != null) {
                parentFragmentManager.beginTransaction()
                    .replace(R.id.container, BrowserFragment())
                    .addToBackStack(null)
                    .commit()
            }
        }

        vm.isBusy.observe(viewLifecycleOwner) { busy ->
            binding.btnConnect.isEnabled = !busy
            binding.progress.visibility = if (busy) View.VISIBLE else View.GONE
        }

        vm.error.observe(viewLifecycleOwner) { err ->
            if (!err.isNullOrEmpty()) {
                Toast.makeText(requireContext(), err, Toast.LENGTH_LONG).show()
                vm.clearError()
            }
        }

        binding.btnConnect.setOnClickListener {
            val host = binding.etHost.text.toString().trim()
            val user = binding.etUser.text.toString().trim()
            if (host.isEmpty() || user.isEmpty()) {
                Toast.makeText(requireContext(), "请填写主机和用户名", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val pass = binding.etPass.text.toString()
            val domain = binding.etDomain.text.toString().trim()
            val remember = binding.cbRemember.isChecked

            // Persist connection config (password only when "记住密码" is checked).
            PrefsStore.save(
                requireContext(),
                PrefsStore.ConnectionPrefs(
                    host = host,
                    user = user,
                    pass = pass,
                    domain = domain,
                    remember = remember
                )
            )

            // No share name is required: after connecting we automatically list the server's
            // shares (the "root directory") and the user taps one to enter it.
            vm.connect(ConnectionParams(host, user, pass, domain))
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

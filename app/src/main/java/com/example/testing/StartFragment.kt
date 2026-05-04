package com.example.testing

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController

class StartFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_start, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<Button>(R.id.startgame).setOnClickListener {
            findNavController().navigate(R.id.action_startFragment_to_gameFragment)
        }
        view.findViewById<Button>(R.id.register).setOnClickListener {
            findNavController().navigate(R.id.action_startFragment_to_endgameFragment)
        }
    }
}

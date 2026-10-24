package com.samourai.sentinel.ui.settings

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.transition.Fade
import androidx.transition.Slide
import androidx.transition.TransitionSet
import com.samourai.sentinel.R
import com.samourai.sentinel.databinding.SettingsActivityBinding
import com.samourai.sentinel.ui.SentinelActivity
import com.samourai.sentinel.ui.home.HomeActivity


class SettingsActivity : SentinelActivity(),
        PreferenceFragmentCompat.OnPreferenceStartFragmentCallback {


    private var requireRestart: Boolean = false
    private lateinit var binding: SettingsActivityBinding


    override fun onCreateAdmitted(savedInstanceState: Bundle?) {
        binding = SettingsActivityBinding.inflate(layoutInflater)
        val view = binding.root
        setContentView(view)
        setSupportActionBar(binding.toolbarSettings)
        if (savedInstanceState == null) {
            showFragment(MainSettingsFragment())
        }
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
    }

    /**
     * #296: hides or restores everything behind the PIN pad for accessibility
     * services.
     *
     * The pad is added to android.R.id.content, so it sits as a sibling of
     * this activity's content root rather than replacing it. It covers the
     * screen visually, but the preference rows and the toolbar's "Navigate
     * up" stayed in the accessibility tree as clickable nodes - a TalkBack
     * user could reach, and potentially activate, controls they cannot see,
     * and text-based UI automation matched them too.
     *
     * NO_HIDE_DESCENDANTS on the content root takes the whole screen behind
     * the pad out of the tree in one step, leaving only the pad reachable.
     * That matches the launch lock screen, where MainActivity happens to have
     * nothing behind the pad to expose.
     */
    fun setContentHiddenFromAccessibility(hidden: Boolean) {
        binding.root.importantForAccessibility =
            if (hidden) View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            else View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
    }

    private fun showFragment(fragment: PreferenceFragmentCompat) {
        supportFragmentManager
                .beginTransaction()
                .replace(R.id.settings, fragment)
                .commit()
    }

    override fun onBackPressed() {
        if (requireRestart) {
            startActivity(Intent(this, HomeActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            })
            overridePendingTransition(R.anim.fade_in, R.anim.bottom_sheet_slide_out)
            finish()
        } else
            super.onBackPressed()
    }

    override fun onSupportNavigateUp(): Boolean {
        if (supportFragmentManager.popBackStackImmediate()) {
            return true
        }
        return super.onSupportNavigateUp()
    }


    fun setRequireRestart(enable: Boolean) {
        requireRestart = enable
    }

    override fun onPreferenceStartFragment(
            caller: PreferenceFragmentCompat,
            pref: Preference
    ): Boolean {
        val args = pref.extras
        val fragment = supportFragmentManager.fragmentFactory.instantiate(
                classLoader,
                pref.fragment!!
        ).apply {
            arguments = args
            setTargetFragment(caller, 0)
        }
        val transitionEnter = TransitionSet()
        val slide = Slide(Gravity.RIGHT)
        transitionEnter.addTransition(slide)
        transitionEnter.addTransition(Fade())
        val transitionExit = TransitionSet()
        transitionExit.addTransition(Slide(Gravity.LEFT))
        transitionExit.addTransition(Fade())
        fragment.enterTransition = transitionEnter
        fragment.exitTransition = transitionExit
        supportFragmentManager.beginTransaction()
                .replace(R.id.settings, fragment)
                .commit()
        title = pref.title
        return true
    }

}
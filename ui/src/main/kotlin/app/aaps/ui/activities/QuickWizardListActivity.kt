package app.aaps.ui.activities

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.SparseArray
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.core.util.forEach
import androidx.core.view.MenuProvider
import androidx.fragment.app.FragmentManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.aaps.core.interfaces.extensions.toVisibility
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.AapsSchedulers
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.fabric.FabricPrivacy
import app.aaps.core.main.utils.ActionModeHelper
import app.aaps.core.main.wizard.QuickWizard
import app.aaps.core.main.wizard.QuickWizardEntry
import app.aaps.core.ui.activities.TranslatedDaggerAppCompatActivity
import app.aaps.core.ui.dialogs.OKDialog
import app.aaps.core.ui.dragHelpers.ItemTouchHelperAdapter
import app.aaps.core.ui.dragHelpers.OnStartDragListener
import app.aaps.core.ui.dragHelpers.SimpleItemTouchHelperCallback
import app.aaps.ui.R
import app.aaps.ui.databinding.ActivityQuickwizardListBinding
import app.aaps.ui.databinding.QuickwizardListItemBinding
import app.aaps.ui.dialogs.EditQuickWizardDialog
import app.aaps.ui.events.EventQuickWizardChange
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.plusAssign
import javax.inject.Inject

class QuickWizardListActivity : TranslatedDaggerAppCompatActivity(), OnStartDragListener {

    @Inject lateinit var aapsSchedulers: AapsSchedulers
    @Inject lateinit var rxBus: RxBus
    @Inject lateinit var fabricPrivacy: FabricPrivacy
    @Inject lateinit var quickWizard: QuickWizard
    @Inject lateinit var dateUtil: DateUtil
    @Inject lateinit var sp: SP
    @Inject lateinit var rh: ResourceHelper

    private var disposable: CompositeDisposable = CompositeDisposable()
    private lateinit var actionHelper: ActionModeHelper<QuickWizardEntry>
    private val itemTouchHelper = ItemTouchHelper(SimpleItemTouchHelperCallback())
    private lateinit var binding: ActivityQuickwizardListBinding

    override fun onStartDrag(viewHolder: RecyclerView.ViewHolder) {
        itemTouchHelper.startDrag(viewHolder)
    }

    private inner class RecyclerViewAdapter(var fragmentManager: FragmentManager) : RecyclerView.Adapter<RecyclerViewAdapter.QuickWizardEntryViewHolder>(), ItemTouchHelperAdapter {

        private inner class QuickWizardEntryViewHolder(val binding: QuickwizardListItemBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): QuickWizardEntryViewHolder {
            val binding = QuickwizardListItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return QuickWizardEntryViewHolder(binding)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onBindViewHolder(holder: QuickWizardEntryViewHolder, position: Int) {
            val entry = quickWizard[position]
            holder.binding.from.text = dateUtil.timeString(entry.validFromDate())
            holder.binding.to.text = dateUtil.timeString(entry.validToDate())
            holder.binding.buttonText.text = entry.buttonText()
            var bindingCarbsTextFull = rh.gs(app.aaps.core.main.R.string.format_carbs, entry.carbs())
            if (entry.useEcarbs() == QuickWizardEntry.YES) {
                bindingCarbsTextFull += " +" + rh.gs(app.aaps.core.main.R.string.format_carbs, entry.carbs2())
                bindingCarbsTextFull += "/" + entry.duration() + "h -> " + entry.time() + "min"
            }
            holder.binding.carbs.text = bindingCarbsTextFull

            holder.binding.root.setOnClickListener {
                if (actionHelper.isNoAction) {
                    val manager = fragmentManager
                    val editQuickWizardDialog = EditQuickWizardDialog()
                    val bundle = Bundle()
                    bundle.putInt("position", position)
                    editQuickWizardDialog.arguments = bundle
                    editQuickWizardDialog.show(manager, "EditQuickWizardDialog")
                } else if (actionHelper.isRemoving) {
                    holder.binding.cbRemove.toggle()
                    actionHelper.updateSelection(position, entry, holder.binding.cbRemove.isChecked)
                }
            }
            holder.binding.sortHandle.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    onStartDrag(holder)
                    return@setOnTouchListener true
                }
                return@setOnTouchListener false
            }
            holder.binding.cbRemove.isChecked = actionHelper.isSelected(position)
            holder.binding.cbRemove.setOnCheckedChangeListener { _, value ->
                actionHelper.updateSelection(position, entry, value)
            }
            holder.binding.sortHandle.visibility = actionHelper.isSorting.toVisibility()
            holder.binding.cbRemove.visibility = actionHelper.isRemoving.toVisibility()
        }

        override fun getItemCount() = quickWizard.size()

        override fun onItemMove(fromPosition: Int, toPosition: Int): Boolean {
            binding.recyclerview.adapter?.notifyItemMoved(fromPosition, toPosition)
            quickWizard.move(fromPosition, toPosition)
            return true
        }

        override fun onDrop() = rxBus.send(EventQuickWizardChange())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityQuickwizardListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        actionHelper = ActionModeHelper(rh, this, null)
        actionHelper.setUpdateListHandler { binding.recyclerview.adapter?.notifyDataSetChanged() }
        actionHelper.setOnRemoveHandler { removeSelected(it) }
        actionHelper.enableSort = true

        title = rh.gs(app.aaps.core.ui.R.string.quickwizard)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)

        binding.recyclerview.setHasFixedSize(true)
        binding.recyclerview.layoutManager = LinearLayoutManager(this)
        binding.recyclerview.adapter = RecyclerViewAdapter(supportFragmentManager)
        itemTouchHelper.attachToRecyclerView(binding.recyclerview)

        binding.addButton.setOnClickListener {
            actionHelper.finish()
            val manager = supportFragmentManager
            val editQuickWizardDialog = EditQuickWizardDialog()
            editQuickWizardDialog.show(manager, "EditQuickWizardDialog")
        }
        addMenuProvider(object : MenuProvider {
            override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                menuInflater.inflate(app.aaps.core.main.R.menu.menu_actions, menu)
            }

            override fun onMenuItemSelected(menuItem: MenuItem): Boolean =
                actionHelper.onOptionsItemSelected(menuItem)
        })
    }

    override fun onResume() {
        super.onResume()
        disposable += rxBus
            .toObservable(EventQuickWizardChange::class.java)
            .observeOn(aapsSchedulers.main)
            .subscribe({
                           val adapter = RecyclerViewAdapter(supportFragmentManager)
                           binding.recyclerview.swapAdapter(adapter, false)
                       }, fabricPrivacy::logException)
    }

    override fun onPause() {
        disposable.clear()
        actionHelper.finish()
        super.onPause()
    }

    private fun removeSelected(selectedItems: SparseArray<QuickWizardEntry>) {
        OKDialog.showConfirmation(this, rh.gs(app.aaps.core.ui.R.string.removerecord), getConfirmationText(selectedItems), Runnable {
            //fix for bug with removal of QuickWizardEntries. Everytime and item is deleted you have to shift the position of to-be-deleted QW to left
            var shiftPostionToLeftFor = 0
            selectedItems.forEach { _, item ->
                quickWizard.remove(item.position - shiftPostionToLeftFor)
                shiftPostionToLeftFor++
                rxBus.send(EventQuickWizardChange())
            }
            actionHelper.finish()
        })
    }

    private fun getConfirmationText(selectedItems: SparseArray<QuickWizardEntry>): String {
        if (selectedItems.size() == 1) {
            val entry = selectedItems.valueAt(0)
            return "${rh.gs(app.aaps.core.ui.R.string.remove_button)} ${entry.buttonText()} ${rh.gs(app.aaps.core.main.R.string.format_carbs, entry.carbs())}\n" +
                "${dateUtil.timeString(entry.validFromDate())} - ${dateUtil.timeString(entry.validToDate())}"
        }
        return rh.gs(app.aaps.core.ui.R.string.confirm_remove_multiple_items, selectedItems.size())
    }

}

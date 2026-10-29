package com.arfoxcode.floatingepub.ui

import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.arfoxcode.floatingepub.R
import com.arfoxcode.floatingepub.data.Book

/** Gabungan Book + persen progress baca, siap dipakai UI */
data class BookUiModel(
    val book: Book,
    val progressPercent: Int // 0-100
)

class BookAdapter(
    private val itemBackgroundColor: Int,
    private val itemTextColor: Int,
    private val itemSecondaryTextColor: Int,
    private val onClick: (Book) -> Unit
) : ListAdapter<BookUiModel, BookAdapter.BookViewHolder>(DIFF_CALLBACK) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BookViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_book, parent, false)
        return BookViewHolder(view)
    }

    override fun onBindViewHolder(holder: BookViewHolder, position: Int) {
        holder.bind(getItem(position), itemBackgroundColor, itemTextColor, itemSecondaryTextColor, onClick)
    }

    class BookViewHolder(itemView: android.view.View) : RecyclerView.ViewHolder(itemView) {
        private val imgCover: ImageView = itemView.findViewById(R.id.imgCover)
        private val txtTitle: TextView = itemView.findViewById(R.id.txtTitle)
        private val txtAuthor: TextView = itemView.findViewById(R.id.txtAuthor)
        private val progressBar: ProgressBar = itemView.findViewById(R.id.progressBar)

        fun bind(
            item: BookUiModel,
            bgColor: Int,
            textColor: Int,
            secondaryTextColor: Int,
            onClick: (Book) -> Unit
        ) {
            itemView.setBackgroundColor(bgColor)
            txtTitle.setTextColor(textColor)
            txtAuthor.setTextColor(secondaryTextColor)

            txtTitle.text = item.book.title
            txtAuthor.text = item.book.author ?: "Penulis tidak diketahui"
            progressBar.progress = item.progressPercent

            imgCover.setImageBitmap(null)
            item.book.coverPath?.let { path ->
                runCatching { BitmapFactory.decodeFile(path) }.getOrNull()?.let {
                    imgCover.setImageBitmap(it)
                }
            }

            itemView.setOnClickListener { onClick(item.book) }
        }
    }

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<BookUiModel>() {
            override fun areItemsTheSame(oldItem: BookUiModel, newItem: BookUiModel) =
                oldItem.book.id == newItem.book.id

            override fun areContentsTheSame(oldItem: BookUiModel, newItem: BookUiModel) =
                oldItem == newItem
        }
    }
}

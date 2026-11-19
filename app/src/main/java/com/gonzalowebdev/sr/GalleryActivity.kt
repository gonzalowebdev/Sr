package com.gonzalowebdev.sr

import android.app.Activity
import android.app.AlertDialog
import android.content.ContentUris
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.text.format.DateFormat
import android.text.format.Formatter
import android.util.LruCache
import android.util.Size
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import java.util.Date
import java.util.concurrent.Executors

class GalleryActivity : Activity() {

    private class Item(
        val uri: Uri,
        val name: String,
        val size: Long,
        val durMs: Long,
        val date: Long,
        val video: Boolean
    )

    private val items = ArrayList<Item>()
    private val adapter = Adapter()
    private val pool = Executors.newFixedThreadPool(2)
    private val cache = object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        setContentView(R.layout.activity_gallery)
        findViewById<View>(R.id.root).padForSystemBars()
        val list = findViewById<ListView>(R.id.list)
        list.adapter = adapter
        list.setOnItemClickListener { _, _, pos, _ -> open(items[pos]) }
        list.setOnItemLongClickListener { _, _, pos, _ ->
            actions(items[pos])
            true
        }
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    override fun onDestroy() {
        pool.shutdownNow()
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req == 77) load()
    }

    private fun load() {
        items.clear()
        items.addAll(query(true))
        items.addAll(query(false))
        items.sortByDescending { it.date }
        adapter.notifyDataSetChanged()
        findViewById<View>(R.id.empty).visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun query(video: Boolean): List<Item> {
        val base = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val cols = if (video) {
            arrayOf(
                MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.DATE_ADDED, MediaStore.Video.Media.DURATION
            )
        } else {
            arrayOf(
                MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.DATE_ADDED
            )
        }
        val path = if (video) "Movies/Sr%" else "Pictures/Sr%"
        val out = ArrayList<Item>()
        try {
            contentResolver.query(
                base, cols, MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ?", arrayOf(path),
                MediaStore.MediaColumns.DATE_ADDED + " DESC"
            )?.use { c ->
                while (c.moveToNext()) {
                    out.add(
                        Item(
                            ContentUris.withAppendedId(base, c.getLong(0)),
                            c.getString(1) ?: "",
                            c.getLong(2),
                            if (video) c.getLong(4) else 0L,
                            c.getLong(3),
                            video
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this, "No se pudo leer la galería", Toast.LENGTH_SHORT).show()
        }
        return out
    }

    private fun open(m: Item) {
        if (m.video) {
            startActivity(Intent(this, PlayerActivity::class.java).setData(m.uri))
        } else {
            val i = Intent(Intent.ACTION_VIEW).setDataAndType(m.uri, "image/png")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            try {
                startActivity(i)
            } catch (e: Exception) {
                Toast.makeText(this, "No hay visor de imágenes", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun actions(m: Item) {
        AlertDialog.Builder(this)
            .setTitle(m.name)
            .setItems(arrayOf("Compartir", "Eliminar")) { _, which ->
                if (which == 0) share(m) else confirmDelete(m)
            }
            .show()
    }

    private fun share(m: Item) {
        val send = Intent(Intent.ACTION_SEND)
            .setType(if (m.video) "video/mp4" else "image/png")
            .putExtra(Intent.EXTRA_STREAM, m.uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, "Compartir"))
    }

    private fun confirmDelete(m: Item) {
        AlertDialog.Builder(this)
            .setTitle("¿Eliminar?")
            .setMessage(m.name)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Eliminar") { _, _ -> delete(m) }
            .show()
    }

    private fun delete(m: Item) {
        try {
            contentResolver.delete(m.uri, null, null)
            load()
        } catch (e: SecurityException) {
            if (Build.VERSION.SDK_INT >= 30) {
                val pi = MediaStore.createDeleteRequest(contentResolver, listOf(m.uri))
                startIntentSenderForResult(pi.intentSender, 77, null, 0, 0, 0)
            } else {
                Toast.makeText(this, "No se pudo eliminar", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun fmtDur(ms: Long): String {
        val s = ms / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }

    private inner class Adapter : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int): Any = items[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: layoutInflater.inflate(R.layout.item_media, parent, false)
            val m = items[position]
            v.findViewById<TextView>(R.id.name).text = m.name
            val date = DateFormat.format("dd/MM/yy HH:mm", Date(m.date * 1000)).toString()
            val parts = ArrayList<String>()
            parts.add(Formatter.formatShortFileSize(this@GalleryActivity, m.size))
            if (m.video) parts.add(fmtDur(m.durMs))
            parts.add(date)
            v.findViewById<TextView>(R.id.meta).text = parts.joinToString(" · ")

            val iv = v.findViewById<ImageView>(R.id.thumb)
            iv.setImageDrawable(null)
            iv.tag = m.uri
            val key = m.uri.toString()
            val cached = cache.get(key)
            if (cached != null) {
                iv.setImageBitmap(cached)
            } else {
                try {
                    pool.execute {
                        try {
                            val bmp = contentResolver.loadThumbnail(m.uri, Size(200, 200), null)
                            cache.put(key, bmp)
                            runOnUiThread { if (iv.tag == m.uri) iv.setImageBitmap(bmp) }
                        } catch (e: Exception) {
                        }
                    }
                } catch (e: Exception) {
                }
            }
            return v
        }
    }
}

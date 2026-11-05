package br.geracaoai.player

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat

/**
 * Listagem única de vídeos do MediaStore — todas as volumes externas (interno + SD),
 * todos os MIME de vídeo, sem filtro de duração/tamanho.
 */
object VideosMedia {
    fun temAcessoCompleto(ctx: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= 33) {
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }

    /** Android 14+: usuário escolheu só alguns itens (acesso parcial). */
    fun temAcessoParcial(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < 34) return false
        if (temAcessoCompleto(ctx)) return false
        return ContextCompat.checkSelfPermission(
            ctx, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun temAlgumAcesso(ctx: Context) = temAcessoCompleto(ctx) || temAcessoParcial(ctx)

    fun colecoes(ctx: Context): List<Uri> {
        if (Build.VERSION.SDK_INT >= 29) {
            val vols = MediaStore.getExternalVolumeNames(ctx)
            if (vols.isNotEmpty()) {
                return vols.map { MediaStore.Video.Media.getContentUri(it) }
            }
        }
        return listOf(MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
    }

    /**
     * @param bucketId se não-nulo, filtra por BUCKET_ID (não pelo nome — nomes repetem).
     */
    fun listar(ctx: Context, bucketId: String? = null): List<Video> {
        val cols = mutableListOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.BUCKET_ID,
            MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Video.Media.DATE_MODIFIED,
        )
        // MIME só para debug/telemetria futura — não filtramos.
        val mimeIxWanted = cols.size
        cols += MediaStore.Video.Media.MIME_TYPE

        val selParts = ArrayList<String>()
        val args = ArrayList<String>()
        if (bucketId != null) {
            selParts += "${MediaStore.Video.Media.BUCKET_ID}=?"
            args += bucketId
        }
        // Não filtra duração/tamanho/mime. Exclui lixeira quando a coluna existe.
        if (Build.VERSION.SDK_INT >= 30) {
            selParts += "(${MediaStore.Video.Media.IS_TRASHED}=0 OR ${MediaStore.Video.Media.IS_TRASHED} IS NULL)"
        }
        val sel = selParts.takeIf { it.isNotEmpty() }?.joinToString(" AND ")
        val argArr = args.takeIf { it.isNotEmpty() }?.toTypedArray()

        val out = ArrayList<Video>()
        val visto = HashSet<String>() // volume+id
        for (uriBase in colecoes(ctx)) {
            try {
                ctx.contentResolver.query(
                    uriBase, cols.toTypedArray(), sel, argArr,
                    "${MediaStore.Video.Media.DATE_MODIFIED} DESC",
                )?.use { c ->
                    val iId = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                    val iNome = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                    val iDur = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                    val iTam = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                    val iBid = c.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_ID)
                    val iBnome = c.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
                    val iMod = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_MODIFIED)
                    while (c.moveToNext()) {
                        val id = c.getLong(iId)
                        val key = "${uriBase}/$id"
                        if (!visto.add(key)) continue
                        val bid = runCatching {
                            if (c.isNull(iBid)) "0" else c.getLong(iBid).toString()
                        }.getOrElse { c.getString(iBid) ?: "0" }
                        out.add(
                            Video(
                                ContentUris.withAppendedId(uriBase, id),
                                c.getString(iNome) ?: "?",
                                if (c.isNull(iDur)) 0L else c.getLong(iDur).coerceAtLeast(0L),
                                if (c.isNull(iTam)) 0L else c.getLong(iTam).coerceAtLeast(0L),
                                bid,
                                c.getString(iBnome)?.takeIf { it.isNotBlank() } ?: "Outros",
                                if (c.isNull(iMod)) 0L else c.getLong(iMod),
                            ),
                        )
                    }
                }
            } catch (_: SecurityException) {
                // volume sem permissão — ignora
            } catch (_: IllegalArgumentException) {
                // coluna IS_TRASHED ausente em algum OEM — tenta de novo sem ela
                try {
                    val sel2 = if (bucketId != null) "${MediaStore.Video.Media.BUCKET_ID}=?" else null
                    val args2 = if (bucketId != null) arrayOf(bucketId) else null
                    ctx.contentResolver.query(
                        uriBase,
                        arrayOf(
                            MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME,
                            MediaStore.Video.Media.DURATION, MediaStore.Video.Media.SIZE,
                            MediaStore.Video.Media.BUCKET_ID, MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
                            MediaStore.Video.Media.DATE_MODIFIED,
                        ),
                        sel2, args2, "${MediaStore.Video.Media.DATE_MODIFIED} DESC",
                    )?.use { c ->
                        while (c.moveToNext()) {
                            val id = c.getLong(0)
                            val key = "${uriBase}/$id"
                            if (!visto.add(key)) continue
                            out.add(
                                Video(
                                    ContentUris.withAppendedId(uriBase, id),
                                    c.getString(1) ?: "?",
                                    c.getLong(2).coerceAtLeast(0L), c.getLong(3).coerceAtLeast(0L),
                                    runCatching { c.getLong(4).toString() }.getOrElse { c.getString(4) ?: "0" },
                                    c.getString(5)?.takeIf { it.isNotBlank() } ?: "Outros",
                                    c.getLong(6),
                                ),
                            )
                        }
                    }
                } catch (_: Throwable) { }
            }
        }
        return out
    }

    fun total(ctx: Context): Int = listar(ctx).size
}

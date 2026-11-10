package br.geracaoai.player

/** Modos do Zoom automático (menu ⋮). O índice é persistido — não reordenar. */
enum class ModoZoom(val rotulo: String) {
    DESLIGADO("Desligado"),
    CORPO("Corpo inteiro"),
    BUCETA("Buceta"),
    BUNDA("Bunda"),
    PEITO_ROSTO("Peito + rosto");

    companion object {
        fun de(i: Int): ModoZoom = entries.getOrElse(i) { DESLIGADO }
    }
}

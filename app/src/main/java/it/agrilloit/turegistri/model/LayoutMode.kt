package it.agrilloit.turegistri.model

/**
 * Modalità di composizione della anteprima e del video composito.
 */
enum class LayoutMode {
    /** Split verticale: camera A a sinistra, camera B a destra. */
    SPLIT_V,

    /** Split orizzontale: camera A sopra, camera B sotto. */
    SPLIT_H,

    /** Picture-in-picture: camera B in inserto sopra la camera A. */
    PIP,

    /** Solo camera A a schermo intero. */
    SINGLE_A,

    /** Solo camera B a schermo intero. */
    SINGLE_B
}

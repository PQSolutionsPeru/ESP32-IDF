// Archivo: domain/model/Stateable.kt
package com.pqsolutions.hdd_monitor.domain.model

/**
 * Interface que define el comportamiento base para entidades que pueden tener un estado
 * y reglas de transición entre estados.
 */
interface Stateable {
    /**
     * El estado actual de la entidad
     */
    val status: String

    /**
     * Verifica si la entidad puede transicionar al nuevo estado especificado
     * @param newStatus El nuevo estado propuesto
     * @return true si la transición es válida, false en caso contrario
     */
    fun canTransitionTo(newStatus: String): Boolean
}
package com.utn.abrigate.data

import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import kotlinx.coroutines.tasks.await

/**
 * Usuario anónimo de Firebase. No se le pide registro a nadie: el uid sirve para
 * asociar la compra de Abrigate Plus a este usuario (obfuscatedAccountId) y el
 * token le prueba al backend quién hace el pedido.
 */
object AuthManager {

    private suspend fun usuario() =
        Firebase.auth.currentUser ?: Firebase.auth.signInAnonymously().await().user
            ?: error("No se pudo iniciar la sesión anónima")

    suspend fun uid(): String = usuario().uid

    suspend fun idToken(): String =
        usuario().getIdToken(false).await().token ?: error("Token vacío")
}

package com.aurelia.app.ui

data class LoginState(
  val serverUrl: String = "",
  val username: String = "",
  val password: String = "",
  val isSubmitting: Boolean = false,
  val error: String? = null,
  val token: String? = null,
  val userId: String? = null,
  val useDynamicColor: Boolean = true,
)

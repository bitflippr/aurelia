package com.aurelia.app.auth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.aurelia_core.AppException

class AuthInterceptorTest {
  @Test
  fun onlyAnHttpUnauthorizedResponseInvalidatesCredentials() {
    assertTrue(AuthInterceptor.isUnauthorizedError(AppException.Http(401u, "expired")))
    assertFalse(AuthInterceptor.isUnauthorizedError(AppException.Http(500u, "authentication service failed")))
    assertFalse(AuthInterceptor.isUnauthorizedError(Exception("Track 401 could not be loaded")))
  }
}

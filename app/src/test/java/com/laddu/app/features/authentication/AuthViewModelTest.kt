package com.laddu.app.features.authentication

import com.laddu.app.core.firebase.AuthRepository
import com.laddu.app.core.firebase.AuthState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private class FakeAuth : AuthRepository {
    override val authState: Flow<AuthState> = MutableStateFlow(AuthState.SignedOut)
    override val currentUid: String? = null
    var result: Result<Unit> = Result.success(Unit)
    val calls = mutableListOf<String>()
    override suspend fun signUp(name: String, email: String, password: String): Result<Unit> { calls += "signUp:$email"; return result }
    override suspend fun signIn(email: String, password: String): Result<Unit> { calls += "signIn:$email"; return result }
    override suspend fun resetPassword(email: String): Result<Unit> { calls += "reset:$email"; return result }
    override suspend fun signOut() { calls += "signOut" }
}

@OptIn(ExperimentalCoroutinesApi::class)
class AuthViewModelTest {
    private val fake = FakeAuth()
    private lateinit var vm: AuthViewModel

    @Before fun setUp() { Dispatchers.setMain(UnconfinedTestDispatcher()); vm = AuthViewModel(fake) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun `invalid input never reaches the network`() {
        vm.signIn("not-an-email", "123")
        assertNotNull(vm.state.value.emailError); assertNotNull(vm.state.value.passwordError)
        assertTrue(fake.calls.isEmpty())
    }

    @Test fun `sign up validates name email and password`() {
        vm.signUp("A", "bad", "x")
        val s = vm.state.value
        assertNotNull(s.nameError); assertNotNull(s.emailError); assertNotNull(s.passwordError)
        assertTrue(fake.calls.isEmpty())
    }

    @Test fun `successful login completes`() {
        vm.signIn("dog@laddu.app", "secret1")
        assertTrue(vm.state.value.success); assertFalse(vm.state.value.loading)
        assertEquals(listOf("signIn:dog@laddu.app"), fake.calls)
    }

    @Test fun `failed login shows a message and stays on the form`() {
        fake.result = Result.failure(IllegalStateException("Firebase is not configured (missing google-services.json)"))
        vm.signIn("dog@laddu.app", "secret1")
        assertFalse(vm.state.value.success)
        assertTrue(vm.state.value.error!!.contains("not configured"))
    }

    @Test fun `network failure is reported in plain language`() {
        fake.result = Result.failure(RuntimeException())
        vm.signUp("Ada Lovelace", "ada@laddu.app", "secret1")
        assertNotNull(vm.state.value.error)
        assertFalse(vm.state.value.success)
    }

    @Test fun `password reset reports success`() {
        vm.reset("dog@laddu.app")
        assertEquals("Password reset email sent. Check your inbox.", vm.state.value.info)
        assertNull(vm.state.value.error)
    }

    @Test fun `clear resets the form state`() {
        vm.signIn("bad", "x"); vm.clear()
        assertEquals(AuthUiState(), vm.state.value)
    }

    @Test fun `validators`() {
        assertNull(AuthValidator.email("a.b+c@d.co")); assertNotNull(AuthValidator.email("a@b"))
        assertNull(AuthValidator.password("123456")); assertNotNull(AuthValidator.password("12345"))
        assertNull(AuthValidator.name("Jo")); assertNotNull(AuthValidator.name(" "))
    }
}

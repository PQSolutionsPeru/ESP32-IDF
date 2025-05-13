package com.pqsolutions.hdd_monitor.domain

import com.pqsolutions.hdd_monitor.data.AuthRepository
import com.pqsolutions.hdd_monitor.data.UserData
import javax.inject.Inject

class LoginUseCase @Inject constructor(
    private val authRepository: AuthRepository
) : UseCase<LoginUseCase.Params, Result<UserData>> {

    override suspend fun invoke(params: Params): Result<UserData> {
        return authRepository.login(params.email, params.password)
    }

    data class Params(val email: String, val password: String)
}
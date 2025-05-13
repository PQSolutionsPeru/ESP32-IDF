package com.pqsolutions.hdd_monitor.domain

interface UseCase<in Params, out Type> {
    suspend operator fun invoke(params: Params): Type
}
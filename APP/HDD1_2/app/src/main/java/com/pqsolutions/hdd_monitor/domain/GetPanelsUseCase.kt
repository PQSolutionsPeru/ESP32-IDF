package com.pqsolutions.hdd_monitor.domain

import com.pqsolutions.hdd_monitor.data.Panel
import com.pqsolutions.hdd_monitor.data.PanelRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class GetPanelsUseCase @Inject constructor(
    private val panelRepository: PanelRepository
) {
    operator fun invoke(params: Params): Flow<List<Panel>> {
        return panelRepository.getPanels(params.clientDocName)
    }

    data class Params(val clientDocName: String?) // Cambiado de clientId a clientDocName
}
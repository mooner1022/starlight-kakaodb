package dev.mooner.kakaodb.api

import dev.mooner.kakaodb.KakaoDBRuntime
import dev.mooner.kakaodb.script.KakaoClient
import dev.mooner.starlight.plugincore.api.Api
import dev.mooner.starlight.plugincore.api.ApiObject
import dev.mooner.starlight.plugincore.api.InstanceType
import dev.mooner.starlight.plugincore.project.Project

/**
 * Registers the database client ([KakaoClient]) as the global `KakaoDB`. It belongs to no API level, so every project
 * gets it; event objects also have it as `db`.
 */
class KakaoDBApi : Api<KakaoClient>() {

    override val name: String = "KakaoDB"

    override val objects: List<ApiObject> =
        getApiObjects<KakaoClient>()

    override val instanceClass: Class<KakaoClient> =
        KakaoClient::class.java

    override val instanceType: InstanceType =
        InstanceType.OBJECT

    override fun getInstance(project: Project): Any =
        KakaoDBRuntime.client
}

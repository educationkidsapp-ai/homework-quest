package quest.feature.staff.data

import quest.api.dto.RegisterDeviceRequest
import quest.feature.push.domain.PushRegistrar
import quest.feature.staff.domain.StaffGateway

/**
 * Feature 13: `POST /me/devices` and `/me/devices/unregister` with **her** bearer, which is what makes the server
 * write the token into `staff_devices` rather than `parent_devices` (`DeviceService` branches on the principal).
 */
class StaffPushRegistrar(private val api: StaffGateway) : PushRegistrar {
    override suspend fun register(device: RegisterDeviceRequest) = api.registerDevice(device)
    override suspend fun unregister(token: String) = api.unregisterDevice(token)
}

package cl.deco.shelf.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GamepadPassthroughTest {

    @Test fun reconoceSoloGamepadConJoystick() {
        val gamepadAndJoystick = 0x00000401 or 0x01000010
        assertTrue(GamepadPassthrough.isGamepadSources(gamepadAndJoystick))
        // Solo DPAD o solo botón sin joystick no debe desactivar el filtro del control remoto
        assertFalse(GamepadPassthrough.isGamepadSources(0x00000101)) // KEYBOARD
        assertFalse(GamepadPassthrough.isGamepadSources(0x00000401)) // solo GAMEPAD sin JOYSTICK
        assertFalse(GamepadPassthrough.isGamepadSources(0x01000010)) // solo JOYSTICK sin GAMEPAD
    }
}

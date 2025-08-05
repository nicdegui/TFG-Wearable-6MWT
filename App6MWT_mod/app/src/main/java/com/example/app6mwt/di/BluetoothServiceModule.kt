package com.example.app6mwt.di

import com.example.app6mwt.bluetooth.BluetoothService
import com.example.app6mwt.bluetooth.BluetoothServiceImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Módulo de Hilt dedicado a proveer la implementación de BluetoothService.
 * Utiliza @Binds para una provisión más eficiente cuando se trata de interfaces e implementaciones.
 */
@Module
@InstallIn(SingletonComponent::class) // Las dependencias definidas aquí tendrán alcance de aplicación (Singleton)
abstract class BluetoothServiceModule {

    /**
     * Vincula la interfaz BluetoothService a su implementación BluetoothServiceImpl.
     * Cuando se solicite una inyección de `BluetoothService`, Hilt proveerá una instancia de `BluetoothServiceImpl`.
     * `@Binds` es preferible a `@Provides` cuando la función simplemente devuelve un parámetro de entrada
     * que es una implementación de la interfaz del tipo de retorno.
     *
     * @param bluetoothServiceImpl La implementación concreta de BluetoothService. Hilt sabrá cómo
     *                             crear una instancia de BluetoothServiceImpl (asumiendo que tiene un
     *                             constructor anotado con @Inject o es proveído por otro módulo).
     * @return Una instancia que implementa BluetoothService.
     */
    @Binds
    @Singleton // Asegura que solo se cree una instancia de BluetoothServiceImpl para toda la aplicación.
    abstract fun bindBluetoothService(
        bluetoothServiceImpl: BluetoothServiceImpl
    ): BluetoothService
}

package com.alexisgordr.icdetector.service;

import android.telephony.CellInfo;
import android.telephony.ServiceState;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyDisplayInfo;
import android.telephony.TelephonyManager;

import androidx.annotation.Nullable;

import java.util.List;

/**
 * 3.1.6 (#49) — Callbacks de telefonía escritos en Java a propósito.
 *
 * Android declara sus parámetros como no nulos, y Kotlin no deja sobrescribirlos como nulables. Un
 * override en Kotlin comprueba la nulidad al entrar: si la capa del fabricante entrega null (algunas
 * lo hacen con la ubicación apagada), lanza una NullPointerException en el hilo principal antes de
 * llegar a la app, y la app se cierra en segundo plano. Java no añade esa comprobación: estos
 * adaptadores pasan el valor tal cual, quizá null, a una función de Kotlin que lo acepta
 * (TelephonyCollectionController). Sin lógica propia: solo reenvían.
 */
final class NullTolerantTelephonyCallbacks {
    private NullTolerantTelephonyCallbacks() {}

    /** Recibe el valor del sistema, que puede ser null. */
    interface Sink<T> {
        void accept(@Nullable T value);
    }

    /** Recibe el error de una petición de celdas. */
    interface ErrorSink {
        void onError(int errorCode, @Nullable Throwable detail);
    }

    /** Callback espontáneo de celdas (TelephonyCallback.CellInfoListener). */
    static final class CellInfoChanged extends TelephonyCallback implements TelephonyCallback.CellInfoListener {
        private final Sink<List<CellInfo>> sink;

        CellInfoChanged(Sink<List<CellInfo>> sink) {
            this.sink = sink;
        }

        @Override
        public void onCellInfoChanged(List<CellInfo> cellInfo) {
            sink.accept(cellInfo);
        }
    }

    /** Respuesta a TelephonyManager.requestCellInfoUpdate. */
    static final class CellInfoRequest extends TelephonyManager.CellInfoCallback {
        private final Sink<List<CellInfo>> sink;
        private final ErrorSink errorSink;

        CellInfoRequest(Sink<List<CellInfo>> sink, ErrorSink errorSink) {
            this.sink = sink;
            this.errorSink = errorSink;
        }

        @Override
        public void onCellInfo(List<CellInfo> cellInfo) {
            sink.accept(cellInfo);
        }

        @Override
        public void onError(int errorCode, Throwable detail) {
            errorSink.onError(errorCode, detail);
        }
    }

    /** Cambios de ServiceState (TelephonyCallback.ServiceStateListener). */
    static final class ServiceStateChanged extends TelephonyCallback implements TelephonyCallback.ServiceStateListener {
        private final Sink<ServiceState> sink;

        ServiceStateChanged(Sink<ServiceState> sink) {
            this.sink = sink;
        }

        @Override
        public void onServiceStateChanged(ServiceState serviceState) {
            sink.accept(serviceState);
        }
    }

    /** Cambios de DisplayInfo (TelephonyCallback.DisplayInfoListener). */
    static final class DisplayInfoChanged extends TelephonyCallback implements TelephonyCallback.DisplayInfoListener {
        private final Sink<TelephonyDisplayInfo> sink;

        DisplayInfoChanged(Sink<TelephonyDisplayInfo> sink) {
            this.sink = sink;
        }

        @Override
        public void onDisplayInfoChanged(TelephonyDisplayInfo displayInfo) {
            sink.accept(displayInfo);
        }
    }
}

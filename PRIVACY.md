# ICdetection — Privacy Policy / Política de privacidad

**Last updated / Última actualización:** 2026-10-05
**App:** ICdetection (`com.alexisgordr.icdetector`)
**Developer / Desarrollador:** Alexis Gómez Rodríguez — alexisgordr@gmail.com

[English](#english) · [Español](#español)

---

## English

ICdetection is an open-source mobile network auditor. It has no accounts, no ads, no analytics and
no tracking. The developer does not run any server and does not receive any of your data.

### What the app accesses and why

| Permission | Used for | Leaves the phone? |
|---|---|---|
| **Precise location** | Android only shares cell information with apps that have location access. GPS coordinates tag each cell observation so the app can compare an antenna with where it was seen before. | **No.** Stored only on the device. |
| **Phone state** | Network state: operator, registration, service and data-network changes, and cellular security events. The app does **not** read calls, SMS, contacts, your phone number, IMSI or IMEI. | **No.** |
| **Notifications** (optional) | The ongoing monitoring notification and anomaly alerts. | **No.** |

While monitoring is on, location is used continuously by a foreground service that always shows a
visible notification. You can stop monitoring at any time.

### Data stored on the device

Cell observations (Cell ID, frequency, signal, timing advance), their GPS position, incidents,
forensic captures and learned routes are stored in a local database on your phone. Android backup is
disabled for the app. You can delete everything from **History → Delete history**, or by uninstalling
the app.

### Data that leaves the device

Only in these optional cases, always over HTTPS:

- **OpenCellID** (off until you add your own API key in Settings). To check whether an antenna exists
  in this public database, the app sends the cell identity (MCC, MNC, area code, Cell ID and radio
  type) and your API key to `opencellid.org`. It never sends your GPS position. A cell identity can
  reveal your approximate area. OpenCellID's own privacy policy applies to that request.
- **Latency check** (off by default). If you enable it, the app sends empty HTTPS `HEAD` requests to
  `www.google.com/generate_204` (Google), `one.one.one.one` (Cloudflare) and `dns.quad9.net` (Quad9)
  to measure network latency. No personal data is included; like any connection, these services see
  your IP address.

### Exports

Exports (CSV, ZIP, forensic packages) are created only when you ask for them and are saved or shared
by you. They may contain precise location and radio data; the app warns you before sharing.

### Children

The app is not directed at children.

### Changes and contact

Changes to this policy are published in this file and in the project history. Questions:
alexisgordr@gmail.com or <https://github.com/alexisgordr/icdetector/issues>.

---

## Español

ICdetection es un auditor de la red móvil de código abierto. No tiene cuentas, ni anuncios, ni
analíticas, ni rastreo. El desarrollador no tiene ningún servidor y no recibe ninguno de tus datos.

### A qué accede la app y para qué

| Permiso | Para qué se usa | ¿Sale del teléfono? |
|---|---|---|
| **Ubicación precisa** | Android solo comparte la información de celdas con apps que tienen acceso a la ubicación. Las coordenadas GPS se asocian a cada observación de celda para comparar una antena con dónde se vio antes. | **No.** Se guarda solo en el dispositivo. |
| **Estado del teléfono** | Estado de la red: operador, registro, cambios de servicio y de red de datos, y eventos de seguridad celular. La app **no** lee llamadas, SMS, contactos, tu número de teléfono, IMSI ni IMEI. | **No.** |
| **Notificaciones** (opcional) | La notificación permanente de monitorización y los avisos de anomalías. | **No.** |

Mientras la monitorización está activa, la ubicación se usa de forma continua desde un servicio en
primer plano que siempre muestra una notificación visible. Puedes detener la monitorización en
cualquier momento.

### Datos guardados en el dispositivo

Las observaciones de celdas (Cell ID, frecuencia, señal, timing advance), su posición GPS, los
incidentes, las capturas forenses y las rutas aprendidas se guardan en una base de datos local en tu
teléfono. La copia de seguridad de Android está desactivada para la app. Puedes borrarlo todo desde
**Historial → Borrar historial**, o desinstalando la app.

### Datos que salen del dispositivo

Solo en estos casos opcionales, siempre por HTTPS:

- **OpenCellID** (desactivado hasta que añades tu propia clave de API en Ajustes). Para comprobar si
  una antena existe en esta base de datos pública, la app envía la identidad de la celda (MCC, MNC,
  código de área, Cell ID y tipo de radio) y tu clave a `opencellid.org`. Nunca envía tu posición
  GPS. La identidad de una celda puede revelar tu zona aproximada. A esa consulta se le aplica la
  política de privacidad de OpenCellID.
- **Medición de latencia** (desactivada por defecto). Si la activas, la app envía peticiones HTTPS
  `HEAD` vacías a `www.google.com/generate_204` (Google), `one.one.one.one` (Cloudflare) y
  `dns.quad9.net` (Quad9) para medir la latencia de la red. No incluyen datos personales; como en
  cualquier conexión, esos servicios ven tu dirección IP.

### Exportaciones

Las exportaciones (CSV, ZIP, paquetes forenses) solo se crean cuando tú las pides y eres tú quien las
guarda o comparte. Pueden contener ubicación precisa y datos de radio; la app te avisa antes de
compartirlas.

### Menores

La app no está dirigida a menores.

### Cambios y contacto

Los cambios en esta política se publican en este archivo y en el historial del proyecto. Dudas:
alexisgordr@gmail.com o <https://github.com/alexisgordr/icdetector/issues>.

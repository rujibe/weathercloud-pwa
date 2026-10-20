# Weathercloud PWA

Panel meteorológico instalable para Android, publicado en GitHub Pages.

## Conexión dinámica con Weathercloud

La página guarda los nombres y los IDs de estaciones en el navegador. Para poder consultar cualquier ID desde el formulario de la web, necesita un pequeño Cloudflare Worker que añade la conexión con Weathercloud y permite las peticiones del navegador.

1. Publica `weathercloud-worker.js` como Worker en Cloudflare.
2. Copia la dirección HTTPS que Cloudflare entrega, con el formato `https://nombre-del-worker.tu-cuenta.workers.dev`.
3. En la PWA, abre **Mis estaciones**, pega esa dirección y pulsa **Conectar**.
4. Añade estaciones con su nombre e ID desde la misma pantalla.

La página consulta todas las estaciones guardadas cada cinco minutos mientras está abierta. El Worker mantiene en caché las condiciones actuales y el histórico del día durante cuatro minutos; los periodos de siete días se guardan quince minutos y los de treinta días, una hora. Weathercloud puede publicar lecturas con menos frecuencia; en ese caso, la página puede recibir el mismo dato varias veces.

El plan gratuito de Cloudflare Workers ofrece hasta 100.000 solicitudes al día. [Límites oficiales](https://developers.cloudflare.com/workers/platform/limits/).

## Histórico

Los gráficos consultan datos reales de Weathercloud para hoy desde las 00:00 locales, ayer, 7 días o 30 días. El gráfico de ayer usa el histórico horario de los últimos siete días y conserva solo el día natural anterior según la hora local del dispositivo. La lluvia muestra también la suma de los registros disponibles para el periodo elegido. La máxima, mínima y lluvia diaria también se calculan desde las 00:00 locales. Algunos sensores no tienen histórico publicado por Weathercloud; en ese caso el gráfico informa que no hay datos. En el periodo de 30 días Weathercloud proporciona datos diarios, no lecturas por hora.

## Previsión para mi ubicación

Pulsa **Usar mi ubicación** y permite el acceso cuando el navegador lo solicite. La aplicación envía las coordenadas a Open-Meteo para la previsión y a OpenStreetMap (Nominatim) para mostrar el nombre y la dirección aproximada; no las guarda en la app. El nombre depende de los datos cartográficos disponibles y puede señalar un lugar cercano, no el edificio exacto. La sección muestra también las coordenadas detectadas. Pulsa **Guardar esta ubicación** para conservar el nombre y las coordenadas solo en este dispositivo; después puedes usar **Usar ubicación guardada** para cargar su previsión sin volver a pedir permiso GPS, o borrarla. Al tocar un día, sus horas se despliegan debajo de la tarjeta en formato de lista y puedes cerrarlas tocando el día otra vez. También puedes buscar ciudades y lugares de cualquier país usando Open-Meteo; el buscador muestra opciones para elegir la localidad correcta y puedes guardar cualquiera de ellas en este dispositivo. La previsión incluye siete días con máximas, mínimas, probabilidad y cantidad de precipitación; al seleccionar un día, muestra temperatura, precipitación probable y viento por hora. Open-Meteo elige automáticamente los modelos adecuados para la ubicación.

## Publicación

En **Settings → Pages**, la fuente de publicación debe ser **GitHub Actions**. El flujo está en `.github/workflows/actualizar-tiempo.yml`.

# Weathercloud PWA

Panel meteorológico instalable para Android, publicado en GitHub Pages.

## Conexión dinámica con Weathercloud

La página guarda los nombres y los IDs de estaciones en el navegador. Para poder consultar cualquier ID desde el formulario de la web, necesita un pequeño Cloudflare Worker que añade la conexión con Weathercloud y permite las peticiones del navegador.

1. Publica `weathercloud-worker.js` como Worker en Cloudflare.
2. Copia la dirección HTTPS que Cloudflare entrega, con el formato `https://nombre-del-worker.tu-cuenta.workers.dev`.
3. En la PWA, abre **Mis estaciones**, pega esa dirección y pulsa **Conectar**.
4. Añade estaciones con su nombre e ID desde la misma pantalla.

La página consulta todas las estaciones guardadas cada cinco minutos mientras está abierta. El Worker mantiene en caché las respuestas de Weathercloud durante diez minutos, que es el intervalo de actualización indicado para estaciones gratuitas. Por eso, la página puede consultar cada cinco minutos, pero Weathercloud podría devolver la misma lectura dos veces.

El plan gratuito de Cloudflare Workers ofrece hasta 100.000 solicitudes al día. [Límites oficiales](https://developers.cloudflare.com/workers/platform/limits/).

## Histórico

Los gráficos consultan datos reales de Weathercloud para 24 horas, 7 días o 30 días. Algunos sensores no tienen histórico publicado por Weathercloud; en ese caso el gráfico informa que no hay datos.

## Publicación

En **Settings → Pages**, la fuente de publicación debe ser **GitHub Actions**. El flujo está en `.github/workflows/actualizar-tiempo.yml`.

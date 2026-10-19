# Weathercloud PWA

Panel meteorológico instalable para Android, publicado gratis en GitHub Pages.

## Actualización de datos

GitHub Actions publica las condiciones actuales cada diez minutos y consulta una vez al día el histórico horario (hasta un año) de los sensores admitidos por Weathercloud. Para cuidar los límites del servicio, las consultas históricas no se repiten en cada actualización de las condiciones actuales.

La página muestra el último año, y permite filtrar 24 horas, 7 días, 30 días o un año. Los sensores sin datos no muestran una curva.

## Añadir estaciones

Para incluir otra estación en las consultas automáticas, agrega su ID y nombre en `stations.json`. Guardarla desde el navegador solo guarda el nombre y el ID en ese dispositivo.

## Publicación

En **Settings → Pages**, la fuente de publicación debe ser **GitHub Actions**. El flujo está en `.github/workflows/actualizar-tiempo.yml`.

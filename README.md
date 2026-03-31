Laboratorio #2 - Hashing Dinámico vs Búsqueda Secuencial

Integrantes:
Jonathan Alzate
Yeferson Alexis Salcedo
Samuel Velásquez Berrio


Objetivo
Desarrollar un sistema de registro y búsqueda de usuarios que permita comparar dos métodos:
Extendible Hashing para búsqueda indexada.
Búsqueda secuencial para recorrido lineal del archivo.
La cédula (`cc`) es la clave principal. No se permiten registros duplicados.
Estructura del proyecto
```text
src/
  Main.java
  ExtendibleHashing.java
  SequentialSearch.java
  Usuario.java

data/
  users.dat
  directory.dat
  buckets.dat
```
Cómo funciona
1. Registro
Cuando se registra un usuario:
Se guarda el registro completo en `users.dat`.
Se inserta la cédula en el índice de `ExtendibleHashing`.
Si la cédula ya existe, el sistema rechaza la inserción.
2. Búsqueda
Cuando se busca una cédula:
Se realiza una búsqueda usando Extendible Hashing.
Se realiza la misma búsqueda usando Sequential Search.
Se muestra el tiempo de cada método en milisegundos.
Idea de Extendible Hashing
El índice usa:
un directorio con punteros,
y buckets donde se almacenan los registros indexados.
Si un bucket se llena:
se divide el bucket,
si hace falta, se duplica el directorio,
y luego se redistribuyen los datos.
Esto hace que la búsqueda sea más rápida que revisar todos los registros uno por uno.
Búsqueda secuencial
Este método recorre `users.dat` desde el inicio hasta encontrar la cédula buscada.
Es más simple de implementar, pero con muchos registros suele ser más lento.
Estado del proyecto
✅ Compilación exitosa sin errores.
✅ Funcionalidad completa de registro y búsqueda.
✅ Manejo robusto de archivos data/ (validación de integridad).
✅ Comparación de tiempos (Hashing vs Búsqueda secuencial).

Cambios recientes (última actualización)
- **Corrección de literales de string:** Se arreglaron saltos de línea partidos en el menú y resultados de búsqueda.
- **Validación robusta de archivos:** El programa ahora verifica que los archivos `data/` tengan el tamaño mínimo esperado antes de usarlos, detectando corrupción.
- **Manejo mejorado de excepciones:** Se evita que EOFException genere mensajes null al registrar usuarios.
- **Gestión de data/:** Los archivos `.dat` están ahora en `.gitignore` para no subirse al repositorio (se regeneran en cada ejecución).

Requisitos
Java 17 o superior recomendado.
No usar bases de datos externas.
Ejecutar desde consola.
Compilación y ejecución
Si los archivos están en `src/`:
```bash
javac src/*.java
java -cp src Main
```

**Nota:** La primera ejecución (o después de opción 4 "Reiniciar archivos") inicializará automáticamente los archivos data/ necesarios.
Menú del programa
Registrar usuario
Buscar usuario
Mostrar estado del hash
Reiniciar archivos
Salir
Ejemplo de salida
```text
Usuario encontrado:
Nombre: Juan
CC: 12345
Correo: juan@email.com
Tiempo búsqueda (Hashing): 0.12 ms
Tiempo búsqueda (Secuencial): 1.87 ms
```
Observaciones importantes
La diferencia de tiempos se nota más cuando hay muchos datos.
El rendimiento depende de la cantidad de registros y de la distribución de las cédulas.
El laboratorio busca demostrar la ventaja del acceso indexado frente al recorrido lineal.
Explicación para exposición
Puedes explicar el proyecto así:
Se registran usuarios.
Cada usuario se guarda en un archivo.
El hashing dinámico crea una estructura de acceso rápido.
La búsqueda secuencial revisa uno por uno.
Se comparan tiempos para ver cuál es más eficiente.
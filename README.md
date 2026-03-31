# Laboratorio #2 - Hashing Dinámico vs Búsqueda Secuencial

**Integrantes:**
- Jonathan Alzate Castaño
- Yeferson Alexis Salcedo Preciado
- Samuel Velásquez Berrio

---

## Objetivo

Desarrollar un sistema de registro y búsqueda de usuarios que compare dos métodos de acceso a datos:

| Método | Descripción |
|---|---|
| **Extendible Hashing** | Búsqueda indexada mediante estructura dinámica |
| **Búsqueda Secuencial** | Recorrido lineal del archivo de datos |

La cédula (`cc`) es la clave principal. No se permiten registros duplicados.

---

## Estructura del proyecto

```
src/
  Main.java
  ExtendibleHashing.java
  SequentialSearch.java
  Usuario.java

data/
  users.dat        <- registros completos de usuarios
  directory.dat    <- directorio del índice hash
  buckets.dat      <- buckets del índice hash
```

---

## Cómo funciona

### Registro de usuario
1. El registro completo se guarda en `users.dat`.
2. La cédula se inserta en el índice de `ExtendibleHashing`.
3. Si la cédula ya existe, el sistema rechaza la inserción.

### Búsqueda de usuario
1. Se ejecuta la búsqueda con **Extendible Hashing**.
2. Se ejecuta la misma búsqueda con **Búsqueda Secuencial**.
3. Se muestra el tiempo de cada método en milisegundos.

---

## Extendible Hashing

El índice está compuesto por:
- Un **directorio** con punteros a buckets.
- **Buckets** donde se almacenan las entradas indexadas `(cc, offset)`.

Cuando un bucket se llena:
1. Se **divide** el bucket.
2. Si es necesario, se **duplica** el directorio.
3. Se **redistribuyen** los datos entre los nuevos buckets.

Esto garantiza que la búsqueda nunca compare más de `BUCKET_SIZE` entradas, sin importar cuántos usuarios estén registrados.

## Búsqueda Secuencial

Recorre `users.dat` desde el inicio hasta encontrar la cédula buscada. Es más simple de implementar, pero su tiempo crece linealmente con la cantidad de registros.

---

## Menú del programa

```
===== LABORATORIO HASHING DINÁMICO =====
1. Registrar usuario
2. Buscar usuario
3. Mostrar estado del hash
4. Reiniciar archivos
5. Generar usuarios automáticamente
6. Salir
```

> **Nota:** La primera ejecución (o después de usar la opción 4) inicializa automáticamente los archivos `data/` necesarios.

---

## Ejemplo de salida

```
Usuario encontrado:
Nombre: Juan
CC: 12345
Correo: juan@email.com
Tiempo búsqueda (Hashing):     0.12 ms
Tiempo búsqueda (Secuencial):  1.87 ms
```
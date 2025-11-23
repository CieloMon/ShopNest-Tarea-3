import java.util.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.net.http.*;
import java.net.URI;

/* =================== Utilidades =================== */
final class Fmt {
    private Fmt(){}
    static final DateTimeFormatter FECHA_HORA = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    static void println(String s){ System.out.println(s); }
    static void print(String s){ System.out.print(s); }
    static String money(double d){ return String.format(Locale.US,"%.2f", d); }

    // Pluralización sencilla en español (aproximada) para nombre de productos
    static String pluralPalabra(String w){
        if(w.isBlank()) return w;
        String lw = w.toLowerCase(Locale.ROOT);
        // reglas rápidas
        if(lw.endsWith("z")) return w.substring(0, w.length()-1) + "ces";          // luz -> luces
        if(lw.endsWith("al")) return w + "es";                                     // artesanal -> artesanales
        if(lw.endsWith("el")) return w + "es";
        if(lw.endsWith("en")) return w + "es";
        if(lw.endsWith("on")) return w + "es";
        if(lw.endsWith("r") || lw.endsWith("l") || lw.endsWith("n") || lw.endsWith("d") || lw.endsWith("j"))
            return w + "es";                                                       // pulgar -> pulgares
        if(lw.endsWith("s") || lw.endsWith("x")) return w;                         // tesis -> tesis
        // vocal
        char last = lw.charAt(lw.length()-1);
        if("aeiouáéíóú".indexOf(last)>=0) return w + "s";                          // taza -> tazas
        return w + "es";
    }

    // Pluraliza nombre compuesto: primera y última palabra (e.g., "Pulsera artesanal" -> "Pulseras artesanales")
    static String pluralNombre(String nombre, int cantidad){
        if(cantidad==1) return nombre;
        String[] t = nombre.trim().split("\\s+");
        if(t.length==1) return pluralPalabra(t[0]);
        // palabras neutras que no se tocan
        Set<String> neutras = Set.of("de","del","la","el","los","las","y","en","para","con","ShopNest","shopnest");
        // primera
        t[0] = neutras.contains(t[0]) ? t[0] : pluralPalabra(t[0]);
        // última
        int last = t.length-1;
        t[last] = neutras.contains(t[last]) ? t[last] : pluralPalabra(t[last]);
        return String.join(" ", t);
    }
}

/* =================== Dominio =================== */
class Producto {
    private final String id;
    private final String nombre;
    private final double precio;
    private int stock;
    private final String emprendedorId;

    public Producto(String id, String nombre, double precio, int stock, String emprendedorId) {
        this.id=id; this.nombre=nombre; this.precio=precio; this.stock=stock; this.emprendedorId=emprendedorId;
    }
    public String getId(){ return id; }
    public String getNombre(){ return nombre; }
    public double getPrecio(){ return precio; }
    public int getStock(){ return stock; }
    public String getEmprendedorId(){ return emprendedorId; }
    public boolean disminuirStock(int c){ if(c<=0) return false; if(stock>=c){ stock-=c; return true; } return false; }
    public void aumentarStock(int c){ if(c>0) stock+=c; }
    @Override public String toString(){ return id+" - "+nombre+"  c/u $"+Fmt.money(precio)+"  stock:"+stock; }
}

class Emprendedor {
    final String id;
    String nombre, empresa, ciudad, estado, telefono, direccion;
    int ventas = 0;
    double montoVendido = 0;
    public Emprendedor(String id, String nombre, String empresa, String ciudad, String estado, String telefono, String direccion){
        this.id=id; this.nombre=nombre; this.empresa=empresa; this.ciudad=ciudad; this.estado=estado; this.telefono=telefono; this.direccion=direccion;
    }
}

class Cliente {
    final String id;
    String nombre, email, direccion;
    final LocalDate fechaRegistro = LocalDate.now();
    int compras = 0;
    double gasto = 0;
    boolean vip = false;
    public Cliente(String id, String nombre, String email, String direccion){
        this.id=id; this.nombre=nombre; this.email=email; this.direccion=direccion;
    }
}

class Carrito {
    private final LinkedHashMap<Producto,Integer> items = new LinkedHashMap<>();
    public void agregar(Producto p, int cant){ items.put(p, items.getOrDefault(p,0)+cant); }
    public boolean contiene(Producto p){ return items.containsKey(p); }
    public int cantidadDe(Producto p){ return items.getOrDefault(p,0); }
    public boolean quitar(Producto p, int cant){
        if(!items.containsKey(p)) return false;
        if(cant<=0) return false;
        int actual = items.get(p);
        if(cant>=actual){ items.remove(p); }
        else { items.put(p, actual-cant); }
        return true;
    }
    public boolean vacio(){ return items.isEmpty(); }
    public Map<Producto,Integer> getItems(){ return items; }
    public void limpiar(){ items.clear(); }
    public double subtotal(){ double s=0; for(var e: items.entrySet()) s+= e.getKey().getPrecio()*e.getValue(); return s; }
    @Override public String toString(){
        if(items.isEmpty()) return "(Carrito vacío)";
        StringBuilder sb=new StringBuilder("— Carrito —\n");
        for(var e: items.entrySet()){
            String nombre = e.getKey().getNombre();
            int cant = e.getValue();
            sb.append("• ").append(e.getKey().getId()).append(" ")
              .append(Fmt.pluralNombre(nombre, cant))
              .append(" | c/u $").append(Fmt.money(e.getKey().getPrecio()))
              .append(" | x").append(cant)
              .append(" | = $").append(Fmt.money(e.getKey().getPrecio()*cant))
              .append("\n");
        }
        sb.append("Subtotal: $").append(Fmt.money(subtotal()));
        return sb.toString();
    }
}

enum EstadoPedido { CREADO, PAGADO, ENVIADO }
enum MetodoPago { TARJETA, EFECTIVO }
enum MetodoEnvio { LOCAL, PAQUETERIA }

class Pedido {
    final String folio = "SN-"+UUID.randomUUID().toString().substring(0,8).toUpperCase();
    final Cliente cliente;
    final Emprendedor vendedor;
    final List<Linea> lineas = new ArrayList<>();
    final LocalDateTime creadoEn = LocalDateTime.now();

    EstadoPedido estado = EstadoPedido.CREADO;
    LocalDateTime pagadoEn, enviadoEn;

    double subtotal, descuento, iva, envio = 69.0, total;

    MetodoPago metodoPago; String transactionId;
    MetodoEnvio metodoEnvio; String servicioPaqueteria, guia; LocalDate fechaEstimada;
    String pinCobro;

    static class Linea {
        final String id, nombre; final double precioUnitario; final int cantidad;
        Linea(Producto p, int c){ id=p.getId(); nombre=p.getNombre(); precioUnitario=p.getPrecio(); cantidad=c; }
        double total(){ return precioUnitario*cantidad; }
    }

    public Pedido(Cliente c, Emprendedor e, Carrito carrito, double descuento){
        this.cliente=c; this.vendedor=e;
        for(var it: carrito.getItems().entrySet()) lineas.add(new Linea(it.getKey(), it.getValue()));
        this.subtotal = carrito.subtotal();
        this.descuento = Math.max(0, descuento);
        this.iva = (subtotal - this.descuento)*0.16;
        this.total = (subtotal - this.descuento) + iva + envio;
    }
    public boolean pagarTarjeta(){ if(estado!=EstadoPedido.CREADO) return false; estado=EstadoPedido.PAGADO; metodoPago=MetodoPago.TARJETA; pagadoEn=LocalDateTime.now(); transactionId="TX-"+UUID.randomUUID().toString().substring(0,10).toUpperCase(); return true; }
    public boolean pagarEfectivo(String pin){ if(estado!=EstadoPedido.CREADO) return false; estado=EstadoPedido.PAGADO; metodoPago=MetodoPago.EFECTIVO; pagadoEn=LocalDateTime.now(); pinCobro=pin; transactionId="CASH-"+UUID.randomUUID().toString().substring(0,6).toUpperCase(); return true; }
    public boolean enviarLocal(){ if(estado!=EstadoPedido.PAGADO) return false; estado=EstadoPedido.ENVIADO; metodoEnvio=MetodoEnvio.LOCAL; enviadoEn=LocalDateTime.now(); fechaEstimada=LocalDate.now().plusDays(1); guia="LOCAL-"+UUID.randomUUID().toString().substring(0,6).toUpperCase(); return true; }
    public boolean enviarPaqueteria(String servicio){ if(estado!=EstadoPedido.PAGADO) return false; estado=EstadoPedido.ENVIADO; metodoEnvio=MetodoEnvio.PAQUETERIA; servicioPaqueteria=servicio; enviadoEn=LocalDateTime.now(); guia="G"+(100000+new Random().nextInt(900000)); fechaEstimada=LocalDate.now().plusDays(3); return true; }
    public String resumen(){
        StringBuilder sb=new StringBuilder();
        sb.append("=== RESUMEN DEL PEDIDO ShopNest ===\n")
          .append("Folio: ").append(folio).append("\n")
          .append("Fecha/Hora: ").append(creadoEn.format(Fmt.FECHA_HORA)).append("\n")
          .append("Estado: ").append(estado).append("\n\n")
          .append("[Cliente]\n  ").append(cliente.nombre).append(" | ").append(cliente.email).append("\n")
          .append("  Dirección: ").append(cliente.direccion).append("\n\n")
          .append("[Vendedor]\n  ").append(vendedor.nombre).append(" (").append(vendedor.empresa).append(")\n")
          .append("  ").append(vendedor.ciudad).append(", ").append(vendedor.estado).append(" | Tel: ").append(vendedor.telefono).append("\n\n")
          .append("Productos:\n");
        for(Linea l: lineas){
            sb.append(" • ").append(l.id).append(" ")
              .append(Fmt.pluralNombre(l.nombre, l.cantidad))
              .append(" | c/u $").append(Fmt.money(l.precioUnitario))
              .append(" | x").append(l.cantidad)
              .append(" | = $").append(Fmt.money(l.total()))
              .append("\n");
        }
        sb.append("\nDesglose:\n")
          .append("Subtotal: $").append(Fmt.money(subtotal)).append("\n")
          .append("Descuento: $").append(Fmt.money(descuento)).append("\n")
          .append("IVA 16%: $").append(Fmt.money(iva)).append("\n")
          .append("Envío: $").append(Fmt.money(envio)).append("\n")
          .append("TOTAL: $").append(Fmt.money(total)).append("\n");
        if(pagadoEn!=null){
            sb.append("\nPago: ").append(metodoPago)
              .append(" | ").append(pagadoEn.format(Fmt.FECHA_HORA));
            if(metodoPago==MetodoPago.TARJETA) sb.append(" | Tx: ").append(transactionId);
            if(metodoPago==MetodoPago.EFECTIVO) sb.append(" | PIN de cobro: ").append(pinCobro);
            sb.append("\n");
        }
        if(enviadoEn!=null){
            sb.append("\nEnvío: ").append(metodoEnvio).append(" | Guía: ").append(guia).append("\n");
            if(metodoEnvio==MetodoEnvio.PAQUETERIA) sb.append("  Servicio: ").append(servicioPaqueteria).append("\n");
            sb.append("  Fecha estimada de entrega: ").append(fechaEstimada).append("\n");
            if(metodoEnvio==MetodoEnvio.LOCAL){
                sb.append("\nDatos para contactar con el vendedor:\n")
                  .append("  ").append(vendedor.nombre).append(" (").append(vendedor.empresa).append(")\n")
                  .append("  Teléfono: ").append(vendedor.telefono).append("\n")
                  .append("  Punto de entrega: ").append(vendedor.direccion).append(", ").append(vendedor.ciudad).append(", ").append(vendedor.estado).append("\n");
            }
        }
        return sb.toString();
    }
}

/* =================== Cliente HTTP Demo =================== */
class ApiCliente {
    private final HttpClient http = HttpClient.newHttpClient();
    String demo(){
        try{
            HttpRequest req = HttpRequest.newBuilder().uri(URI.create("https://dummyjson.com/products/1")).GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            return (resp.statusCode()==200)? "HTTP 200 demo OK" : "HTTP demo status: "+resp.statusCode();
        }catch(Exception e){ return "HTTP demo error: "+e.getMessage(); }
    }
}

/* =================== Servicio principal =================== */
class ShopNestService {
    final Map<String,Cliente> clientes = new LinkedHashMap<>();
    final Map<String,Emprendedor> emprendedores = new LinkedHashMap<>();
    final List<Producto> catalogo = new ArrayList<>();
    final ApiCliente api = new ApiCliente();
    final List<Pedido> historialPedidos = new ArrayList<>();

    Cliente clienteActivo = null;
    Emprendedor vendedorActivo = null;
    final Carrito carrito = new Carrito();
    Pedido pedidoActual = null;

    double descuentoActual = 0.0;
    String cuponUsado = "";

    public ShopNestService(){
        Emprendedor e1 = new Emprendedor("E001","Dafne Torres","Hecho en Xalapa","Xalapa","Veracruz","228-555-0123","Av. Ávila Camacho 100");
        emprendedores.put(e1.id, e1);
        vendedorActivo = e1;

        catalogo.add(new Producto("P001","Pulsera artesanal",120.0,20,e1.id));
        catalogo.add(new Producto("P002","Playera local",250.0,15,e1.id));
        catalogo.add(new Producto("P003","Taza ShopNest",99.0,30,e1.id));
    }

    /* ===== Helpers ===== */
    String generarClienteId(){ return String.format("C%03d", clientes.size()+1); }
    Cliente buscarClientePorEmail(String email){
        for(Cliente c: clientes.values()) if(c.email.equalsIgnoreCase(email)) return c;
        return null;
    }

    /* ===== Clientes ===== */
    boolean registrarClienteAuto(String nombre, String email, String direccion){
        if(nombre.isBlank()||email.isBlank()||direccion.isBlank()) return false;
        if(buscarClientePorEmail(email)!=null) return false;
        String id = generarClienteId();
        clientes.put(id, new Cliente(id,nombre,email,direccion));
        clienteActivo = clientes.get(id);
        Fmt.println("✔ Cliente registrado con ID: "+id);
        return true;
    }
    boolean seleccionarCliente(String id){
        Cliente c = clientes.get(id);
        if(c==null) return false;
        clienteActivo = c;
        Fmt.println("— Cliente seleccionado —");
        Fmt.println("ID: "+c.id);
        Fmt.println("Nombre: "+c.nombre);
        Fmt.println("Email: "+c.email);
        Fmt.println("Dirección: "+c.direccion);
        Fmt.println("VIP: "+c.vip+" | Compras: "+c.compras+" | Gasto: $"+Fmt.money(c.gasto));
        return true;
    }
    void listarClientesVertical(){
        if(clientes.isEmpty()){ Fmt.println("(No hay clientes registrados)"); return; }
        Fmt.println("— Clientes —");
        for(Cliente c: clientes.values()){
            Fmt.println("ID: "+c.id);
            Fmt.println("  Nombre: "+c.nombre);
            Fmt.println("  Email: "+c.email);
            Fmt.println("  VIP: "+c.vip+" | Compras: "+c.compras+" | Gasto: $"+Fmt.money(c.gasto));
            Fmt.println("  Dirección: "+c.direccion);
            Fmt.println("");
        }
    }

    /* ===== Emprendedores (lista + selección fusionadas) ===== */
    void listarYSeleccionarEmprendedor(Scanner sc){
        if(emprendedores.isEmpty()){ Fmt.println("(No hay emprendedores)"); return; }
        Fmt.println("— Emprendedores —");
        for(Emprendedor e: emprendedores.values()){
            // productos que vende
            List<String> productos = new ArrayList<>();
            for(Producto p: catalogo) if(p.getEmprendedorId().equals(e.id)) productos.add(p.getId()+" "+p.getNombre());
            Fmt.println("ID: "+e.id);
            Fmt.println("  Nombre: "+e.nombre+" ("+e.empresa+")");
            Fmt.println("  Ciudad/Estado: "+e.ciudad+", "+e.estado);
            Fmt.println("  Teléfono: "+e.telefono);
            Fmt.println("  Dirección: "+e.direccion);
            Fmt.println("  Ventas: "+e.ventas+" | Monto vendido: $"+Fmt.money(e.montoVendido));
            Fmt.println("  Productos: "+(productos.isEmpty()? "(sin productos)": String.join(", ", productos)));
            Fmt.println("");
        }
        Fmt.print("ID del emprendedor a activar (0 para cancelar): ");
        String id = sc.nextLine().trim();
        if(id.equals("0")) return;
        Emprendedor sel = emprendedores.get(id);
        if(sel==null){ Fmt.println("⚠ No existe ese ID."); return; }
        vendedorActivo = sel;
        Fmt.println("✔ Vendedor activo: "+sel.id+" - "+sel.nombre+" ("+sel.empresa+")");
    }

    /* ===== Catálogo / Carrito ===== */
    void verCatalogo(){
        Fmt.println("— Catálogo —");
        for(Producto p: catalogo) Fmt.println("• "+p);
    }
    Producto buscarProducto(String id){ for(Producto p: catalogo) if(p.getId().equalsIgnoreCase(id)) return p; return null; }

    boolean agregarAlCarrito(String id, int cant){
        if(clienteActivo==null){ Fmt.println("⚠ Primero selecciona o da de alta a un cliente (opción 1)."); return false; }
        Producto p = buscarProducto(id);
        if(p==null){ Fmt.println("⚠ No existe el producto "+id); return false; }
        if(cant<=0){ Fmt.println("⚠ Cantidad inválida."); return false; }
        if(p.getStock()<cant){ Fmt.println("⚠ Stock insuficiente. Disponible: "+p.getStock()); return false; }
        p.disminuirStock(cant);
        carrito.agregar(p,cant);
        Fmt.println("✔ Agregado: "+Fmt.pluralNombre(p.getNombre(), cant)+" x"+cant+"  (c/u $"+Fmt.money(p.getPrecio())+")");
        Fmt.println("Subtotal ahora: $"+Fmt.money(carrito.subtotal()));
        return true;
    }

    void listarParaQuitar(){
        if(carrito.vacio()){ Fmt.println("(Carrito vacío)"); return; }
        Fmt.println("— Productos en carrito —");
        for(var e: carrito.getItems().entrySet()){
            String nombre = Fmt.pluralNombre(e.getKey().getNombre(), e.getValue());
            Fmt.println(e.getKey().getId()+" • "+nombre+" • c/u $"+Fmt.money(e.getKey().getPrecio())+" • en carrito: "+e.getValue());
        }
    }

    boolean quitarDelCarrito(String id, int cant){
        Producto p = buscarProducto(id);
        if(p==null || !carrito.contiene(p)){ Fmt.println("⚠ Ese producto no está en el carrito."); return false; }
        int prev = carrito.cantidadDe(p);
        if(cant<=0){ Fmt.println("⚠ Cantidad inválida."); return false; }
        boolean ok = carrito.quitar(p, cant);
        int actual = carrito.cantidadDe(p);
        int retirados = Math.min(cant, prev);
        p.aumentarStock(retirados);
        if(ok){
            String np = Fmt.pluralNombre(p.getNombre(), retirados);
            String nq = Fmt.pluralNombre(p.getNombre(), actual);
            Fmt.println("✔ Se retiraron "+retirados+" "+np+". Quedan "+actual+" "+nq+" en el carrito.");
            Fmt.println("Subtotal ahora: $"+Fmt.money(carrito.subtotal()));
        }
        return ok;
    }

    void verCarrito(){ Fmt.println(carrito.toString()); }

    /* ===== Cupones ===== */
    boolean aplicarCupon(String code, Scanner sc){
        if(carrito.vacio()){ Fmt.println("⚠ El carrito está vacío."); return false; }
        code = code.toUpperCase(Locale.ROOT).trim();
        double base = carrito.subtotal();

        switch(code){
            case "SHOP10" -> {
                descuentoActual = base*0.10; cuponUsado=code;
                Fmt.println("✔ SHOP10 aplicado: -$"+Fmt.money(descuentoActual));
                return true;
            }
            case "BIENVENIDA15" -> {
                Fmt.print("Correo del cliente (0 para cancelar): ");
                String correo = sc.nextLine().trim();
                if(correo.equals("0")){ Fmt.println("Cupón cancelado."); return false; }
                Cliente c = buscarClientePorEmail(correo);
                if(c==null){ Fmt.println("⚠ Ese correo no existe en el sistema."); return false; }
                if(c.compras>0){ Fmt.println("⚠ BIENVENIDA15 aplica solo en la primera compra."); return false; }
                descuentoActual = base*0.15; cuponUsado=code;
                Fmt.println("✔ BIENVENIDA15 aplicado: -$"+Fmt.money(descuentoActual));
                return true;
            }
            case "VIP20" -> {
                Fmt.print("Correo del cliente (0 para cancelar): ");
                String correo = sc.nextLine().trim();
                if(correo.equals("0")){ Fmt.println("Cupón cancelado."); return false; }
                Cliente c = buscarClientePorEmail(correo);
                if(c==null){ Fmt.println("⚠ Ese correo no existe en el sistema."); return false; }
                if(!c.vip){
                    int comprasRest = Math.max(0, 3 - c.compras);
                    double montoRest = Math.max(0.0, 2000.0 - c.gasto);
                    Fmt.println("⚠ VIP20 es solo para clientes VIP. Te faltan "
                        + comprasRest + " compras o $" + Fmt.money(montoRest)
                        + " de gasto acumulado. También puedes adquirir membresía VIP por $99/mes.");
                    return false;
                }
                descuentoActual = base*0.20; cuponUsado=code;
                Fmt.println("✔ VIP20 aplicado: -$"+Fmt.money(descuentoActual));
                return true;
            }
            default -> { Fmt.println("⚠ Cupón inválido."); return false; }
        }
    }

    /* ===== Pedido ===== */
    boolean confirmarPedido(){
        if(clienteActivo==null){ Fmt.println("⚠ Selecciona cliente en la opción 1."); return false; }
        if(vendedorActivo==null){ Fmt.println("⚠ Selecciona vendedor (opción 14)."); return false; }
        if(carrito.vacio()){ Fmt.println("⚠ El carrito está vacío."); return false; }
        pedidoActual = new Pedido(clienteActivo, vendedorActivo, carrito, descuentoActual);
        carrito.limpiar(); descuentoActual=0.0; cuponUsado="";
        Fmt.println("✔ Pedido creado. Folio: "+pedidoActual.folio);
        Fmt.println(pedidoActual.resumen());
        return true;
    }

    /* ===== Pago ===== */
    static boolean nombreValido(String n){
        // Letras Unicode con espacios, apóstrofes o guiones (acepta acentos). Mínimo 2 letras.
        return n!=null && n.trim().matches("(?iu)^[\\p{L}]{2,}(?:[ '\\-][\\p{L}]{2,})*$");
    }
    static boolean solo16Digitos(String s){ return s!=null && s.matches("^\\d{16}$"); }
    static boolean mesValido(String m){ return m!=null && m.matches("0[1-9]|1[0-2]"); }
    static boolean cvcValido(String c){ return c!=null && c.matches("^\\d{3,4}$"); }

    boolean pagarTarjetaInteractivo(Scanner sc){
        if(pedidoActual==null){ Fmt.println("⚠ No hay pedido para pagar."); return false; }
        if(pedidoActual.estado!=EstadoPedido.CREADO){ Fmt.println("⚠ El pedido no está en estado para pagar."); return false; }

        String nombre, numero, mes, anio, cvc;

        // Nombre
        while(true){
            Fmt.print("Titular (nombre y apellidos) — 0 para cancelar: ");
            nombre = sc.nextLine().trim();
            if(nombre.equals("0")){ Fmt.println("Pago cancelado."); return false; }
            if(nombreValido(nombre)) break;
            Fmt.println("⚠ Nombre inválido. Usa solo letras y espacios (ej. Cielo Monterrubio Martínez).");
        }

        // Número: aceptar espacios
        while(true){
            Fmt.print("Número de tarjeta (16 dígitos) — 0 para cancelar: ");
            numero = sc.nextLine().replaceAll("\\s+","");
            if(numero.equals("0")){ Fmt.println("Pago cancelado."); return false; }
            if(solo16Digitos(numero)) break;
            Fmt.println("⚠ Debe contener exactamente 16 dígitos.");
        }

        // Mes
        while(true){
            Fmt.print("Mes (01-12) — 0 para cancelar: ");
            mes = sc.nextLine().trim();
            if(mes.equals("0")){ Fmt.println("Pago cancelado."); return false; }
            if(mesValido(mes)) break; else Fmt.println("⚠ Mes inválido.");
        }

        // Año (yyyy no vencido)
        while(true){
            Fmt.print("Año (yyyy) — 0 para cancelar: ");
            anio = sc.nextLine().trim();
            if(anio.equals("0")){ Fmt.println("Pago cancelado."); return false; }
            if(anio.matches("\\d{4}")){
                int y=Integer.parseInt(anio), m=Integer.parseInt(mes);
                if(!YearMonth.of(y,m).isBefore(YearMonth.now())) break;
            }
            Fmt.println("⚠ Año inválido o tarjeta vencida.");
        }

        // CVC
        while(true){
            Fmt.print("CVC (3-4) — 0 para cancelar: ");
            cvc = sc.nextLine().trim();
            if(cvc.equals("0")){ Fmt.println("Pago cancelado."); return false; }
            if(cvcValido(cvc)) break; else Fmt.println("⚠ CVC inválido.");
        }

        boolean ok = pedidoActual.pagarTarjeta();
        if(ok){
            Fmt.println("✔ Pago con tarjeta aceptado. Tx: "+pedidoActual.transactionId);
            cerrarCompraImpactos();
        }
        return ok;
    }

    boolean pagarEfectivo(){
        if(pedidoActual==null){ Fmt.println("⚠ No hay pedido para pagar."); return false; }
        if(pedidoActual.estado!=EstadoPedido.CREADO){ Fmt.println("⚠ El pedido no está en estado para pagar."); return false; }
        String pin = String.valueOf(1000 + new Random().nextInt(9000));
        boolean ok = pedidoActual.pagarEfectivo(pin);
        if(ok){
            Fmt.println("✔ Orden de cobro generada (EFECTIVO). PIN: "+pin+" | Ref: "+pedidoActual.transactionId);
            Fmt.println("Presenta este PIN al vendedor para confirmar tu pago contraentrega.");
            cerrarCompraImpactos();
        }
        return ok;
    }

    private void cerrarCompraImpactos(){
        // Actualiza métricas y guarda en historial
        clienteActivo.compras += 1;
        clienteActivo.gasto += pedidoActual.total;
        vendedorActivo.ventas += 1;
        vendedorActivo.montoVendido += pedidoActual.total;
        historialPedidos.add(pedidoActual);
        if(!clienteActivo.vip && (clienteActivo.compras>=3 || clienteActivo.gasto>=2000.0)){
            clienteActivo.vip = true;
            Fmt.println("✔ ¡Felicidades! Te has convertido en CLIENTE VIP.");
        }
    }

    /* ===== Envío ===== */
    boolean enviarLocal(){
        if(pedidoActual==null || pedidoActual.estado!=EstadoPedido.PAGADO){ Fmt.println("⚠ Primero paga el pedido."); return false; }
        boolean ok = pedidoActual.enviarLocal();
        if(ok){
            int totalPiezas = 0; for(Pedido.Linea l: pedidoActual.lineas) totalPiezas += l.cantidad;
            Fmt.println("✔ Envío LOCAL confirmado.");
            Fmt.println("Se entregará tu pedido de "+totalPiezas+" pieza(s) por entrega local.");
            Fmt.println("Datos para contactar con el vendedor:");
            Fmt.println("  "+vendedorActivo.nombre+" ("+vendedorActivo.empresa+")");
            Fmt.println("  Teléfono: "+vendedorActivo.telefono);
            Fmt.println("  Punto de entrega: "+vendedorActivo.direccion+", "+vendedorActivo.ciudad+", "+vendedorActivo.estado);
            Fmt.println("Guía: "+pedidoActual.guia);
            Fmt.println("Fecha estimada de entrega: "+pedidoActual.fechaEstimada);
        }
        return ok;
    }

    boolean enviarPaqueteria(String servicio){
        if(pedidoActual==null || pedidoActual.estado!=EstadoPedido.PAGADO){ Fmt.println("⚠ Primero paga el pedido."); return false; }
        boolean ok = pedidoActual.enviarPaqueteria(servicio);
        if(ok){
            int totalPiezas = 0; for(Pedido.Linea l: pedidoActual.lineas) totalPiezas += l.cantidad;
            Fmt.println("✔ Orden de envío generada.");
            Fmt.println("Se enviará tu pedido de "+totalPiezas+" pieza(s) por "+servicio+".");
            Fmt.println("[Remitente] "+vendedorActivo.nombre+" - "+vendedorActivo.empresa+" | "+vendedorActivo.ciudad+", "+vendedorActivo.estado);
            Fmt.println("[Destinatario] "+clienteActivo.nombre+" | "+clienteActivo.direccion);
            Fmt.println("Guía: "+pedidoActual.guia);
            Fmt.println("Fecha estimada de entrega: "+pedidoActual.fechaEstimada);
        }
        return ok;
    }

    /* ===== Resumen / Historial / HTTP ===== */
    void verResumen(){ if(pedidoActual==null){ Fmt.println("⚠ No hay pedido actual."); return; } Fmt.println(pedidoActual.resumen()); }

    void verHistorialCliente(String id){
        Cliente c = clientes.get(id);
        if(c==null){ Fmt.println("⚠ El cliente no existe."); return; }
        Fmt.println("— Historial de "+c.nombre+" —");
        Fmt.println("Compras: "+c.compras);
        Fmt.println("Gasto total: $"+Fmt.money(c.gasto));
        Fmt.println("VIP: "+c.vip);
        boolean hay = false;
        for(Pedido p: historialPedidos){
            if(p.cliente.id.equals(id)){
                hay = true;
                Fmt.println("  Folio: "+p.folio+" | Fecha: "+p.creadoEn.format(Fmt.FECHA_HORA)+" | Total: $"+Fmt.money(p.total));
                Fmt.println("  Productos:");
                for(Pedido.Linea l: p.lineas){
                    Fmt.println("    - "+Fmt.pluralNombre(l.nombre, l.cantidad)+" x"+l.cantidad+" (c/u $"+Fmt.money(l.precioUnitario)+") = $"+Fmt.money(l.total()));
                }
            }
        }
        if(!hay) Fmt.println("  (Sin compras registradas)");
    }

    String httpDemo(){ return api.demo(); }

    /* ===== Reinicio suave para nueva compra ===== */
    void nuevaCompra(){
        pedidoActual = null; // carrito ya fue limpiado al confirmar pedido
        Fmt.println("🛒 Puedes iniciar una nueva compra: agrega productos, aplica cupón y confirma pedido.");
    }
}

/* =================== Interfaz por Consola =================== */
public class Main {

    static void menu(){
        Fmt.println("\n====== ShopNest ======");
        Fmt.println("1. Alta/Selección de cliente");
        Fmt.println("2. Ver catálogo");
        Fmt.println("3. Agregar producto (ID, cantidad)");
        Fmt.println("4. Quitar producto del carrito");
        Fmt.println("5. Ver carrito");
        Fmt.println("6. Aplicar cupón (SHOP10 / BIENVENIDA15 / VIP20)");
        Fmt.println("7. Confirmar pedido");
        Fmt.println("8. Pagar (tarjeta / efectivo)");
        Fmt.println("9. Enviar (local / paquetería)");
        Fmt.println("10. Ver resumen del pedido");
        Fmt.println("11. Ver historial del cliente");
        Fmt.println("12. Probar integración HTTP");
        Fmt.println("13. Listar clientes");
        Fmt.println("14. Emprendedores: listar y seleccionar activo");
        Fmt.println("0. Salir");
        Fmt.print("Opción: ");
    }

    public static void main(String[] args){
        // Fuerza consola UTF-8 (si el entorno lo soporta)
        try {
            java.nio.charset.Charset utf8 = java.nio.charset.StandardCharsets.UTF_8;
            System.setOut(new java.io.PrintStream(System.out, true, utf8));
            System.setErr(new java.io.PrintStream(System.err, true, utf8));
        } catch (Exception ignored) {}

        ShopNestService s = new ShopNestService();
        try(Scanner sc = new Scanner(System.in, "UTF-8")){
            int op;
            boolean seguir = true;
            while(seguir){
                menu();
                while(!sc.hasNextInt()){ Fmt.print("Número: "); sc.next(); }
                op = sc.nextInt(); sc.nextLine();

                switch(op){
                    case 1 -> {
                        Fmt.print("¿Dar de alta a un nuevo cliente? (s/n, 0 para menú): ");
                        String r = sc.nextLine().trim().toLowerCase();
                        if(r.equals("0")) break;
                        if("s".equals(r)){
                            Fmt.print("Nombre completo: "); String n=sc.nextLine();
                            if(n.equals("0")) break;
                            Fmt.print("Email: "); String e=sc.nextLine(); if(e.equals("0")) break;
                            Fmt.print("Dirección: "); String d=sc.nextLine(); if(d.equals("0")) break;
                            if(!s.registrarClienteAuto(n,e,d))
                                Fmt.println("⚠ No se pudo registrar. Verifica que el email no exista y que los datos no estén vacíos.");
                        }else{
                            s.listarClientesVertical();
                            if(s.clientes.isEmpty()) break;
                            Fmt.print("ID de cliente a seleccionar (0 para cancelar): ");
                            String id=sc.nextLine().trim();
                            if(id.equals("0")) break;
                            if(!s.seleccionarCliente(id)) Fmt.println("⚠ No se encontró el cliente.");
                        }
                    }
                    case 2 -> s.verCatalogo();
                    case 3 -> {
                        Fmt.print("ID producto (0 para cancelar): "); String id=sc.nextLine();
                        if(id.equals("0")) break;
                        Fmt.print("Cantidad (0 para cancelar): "); String ct=sc.nextLine().trim();
                        if(ct.equals("0")) break;
                        int c = Integer.parseInt(ct);
                        s.agregarAlCarrito(id,c);
                    }
                    case 4 -> {
                        s.listarParaQuitar();
                        if(s.carrito.vacio()) break;
                        Fmt.print("ID a retirar (0 para cancelar): "); String id=sc.nextLine();
                        if(id.equals("0")) break;
                        Fmt.print("Cantidad a retirar (0 para cancelar): "); String t=sc.nextLine().trim();
                        if(t.equals("0")) break;
                        int c = Integer.parseInt(t);
                        s.quitarDelCarrito(id,c);
                    }
                    case 5 -> s.verCarrito();
                    case 6 -> {
                        Fmt.println("Disponibles: SHOP10 (10%), BIENVENIDA15 (primera compra con correo), VIP20 (VIP con correo).");
                        Fmt.print("Código (0 para cancelar): "); String code=sc.nextLine();
                        if(code.equals("0")) break;
                        s.aplicarCupon(code, sc);
                    }
                    case 7 -> s.confirmarPedido();
                    case 8 -> {
                        Fmt.print("Método (tarjeta/efectivo, 0 para cancelar): ");
                        String m=sc.nextLine().trim().toLowerCase();
                        if(m.equals("0")) break;
                        boolean ok = m.startsWith("t") ? s.pagarTarjetaInteractivo(sc) : s.pagarEfectivo();
                        if(ok){
                            Fmt.print("¿Deseas comprar algo más? (s/n): ");
                            String mas = sc.nextLine().trim().toLowerCase();
                            if(mas.startsWith("s")) s.nuevaCompra();
                        }
                    }
                    case 9 -> {
                        Fmt.print("Envío (local/paquetería, 0 para cancelar): ");
                        String em=sc.nextLine().trim().toLowerCase();
                        if(em.equals("0")) break;
                        if(em.startsWith("l")) s.enviarLocal();
                        else {
                            Fmt.print("Servicio (Estafeta/DHL/FedEx, 0 para cancelar): ");
                            String serv=sc.nextLine();
                            if(serv.equals("0")) break;
                            s.enviarPaqueteria(serv);
                        }
                    }
                    case 10 -> s.verResumen();
                    case 11 -> {
                        s.listarClientesVertical();
                        if(s.clientes.isEmpty()) break;
                        Fmt.print("ID del cliente a consultar (0 para cancelar): "); String id=sc.nextLine();
                        if(id.equals("0")) break;
                        s.verHistorialCliente(id);
                    }
                    case 12 -> Fmt.println(s.httpDemo());
                    case 13 -> s.listarClientesVertical();
                    case 14 -> s.listarYSeleccionarEmprendedor(sc);
                    case 0 -> { Fmt.println("¡Gracias por usar ShopNest!"); seguir=false; }
                    default -> Fmt.println("⚠ Opción inválida.");
                }
            }
        }
    }
}
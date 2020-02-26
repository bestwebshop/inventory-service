package tech.bestwebshop.api.productcomposite;

import com.netflix.hystrix.contrib.javanica.annotation.HystrixCommand;
import com.netflix.hystrix.contrib.javanica.annotation.HystrixProperty;
import org.springframework.cloud.client.circuitbreaker.EnableCircuitBreaker;
import org.springframework.cloud.netflix.hystrix.EnableHystrix;
import org.springframework.http.*;
import org.springframework.lang.Nullable;
import org.springframework.security.oauth2.common.exceptions.OAuth2Exception;
import org.springframework.security.oauth2.provider.OAuth2Authentication;
import org.springframework.security.oauth2.provider.authentication.OAuth2AuthenticationDetails;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import tech.bestwebshop.api.productcomposite.model.*;

import javax.annotation.security.RolesAllowed;
import javax.validation.Valid;
import java.util.*;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import static java.util.Objects.requireNonNull;

@Component
@EnableHystrix
@RestController
@EnableCircuitBreaker
@CrossOrigin(origins = "*", allowedHeaders = "*")
public class InventoryController {

    private static final Logger LOGGER = Logger.getLogger(InventoryController.class.getSimpleName());

    private final Map<Integer, Product> productCache = new LinkedHashMap<>();
    private final Map<Integer, Category> categoryCache = new LinkedHashMap<>();

    private static final String PRODUCT_SERVICE_URL = "http://product-service/products";
    private static final String CATEGORY_SERVICE_URL = "http://category-service/categories";
    private static final String MAX_PRICE = "1e10";
    private static final String MIN_PRICE = "-1e10";

    private final RestTemplate restTemplate;

    public InventoryController(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    @HystrixCommand(fallbackMethod = "getProductCache", commandProperties = {
            @HystrixProperty(name = "circuitBreaker.requestVolumeThreshold", value = "2")
    })
    @GetMapping("/products/{id}")
    @RolesAllowed({"USER"})
    public ResponseEntity<Product> getProduct(@PathVariable(value = "id") Long productId, OAuth2Authentication auth) {
        LOGGER.info("Get Product with ID " + productId);
        ResponseEntity<CoreProduct> coreProductEntity;
        try {
            coreProductEntity = restTemplate.exchange(PRODUCT_SERVICE_URL + "/" + productId,
                    HttpMethod.GET, buildHttpEntity(auth), CoreProduct.class);
        } catch (HttpClientErrorException.NotFound ex) {
            return ResponseEntity.notFound().build();
        } catch (OAuth2Exception ex) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        } catch (Exception ex) {
            LOGGER.warning("[InventoryService#getProduct] Exception: " + ex.getMessage() + "\n" + ex.getStackTrace());
            return ResponseEntity.notFound().build();
        }
        CoreProduct tmpCoreProduct = requireNonNull(coreProductEntity.getBody());
        System.out.println("[InventoryService#getProduct] Got Core Product " + tmpCoreProduct);

        ResponseEntity<Category> coreCategoryEntity = getCategory(tmpCoreProduct.getCategoryID(), auth);
        HttpStatus categoryStatus = coreCategoryEntity.getStatusCode();
        if(!wasCallSuccessful(coreCategoryEntity) && categoryStatus != HttpStatus.NOT_FOUND){
            if(categoryStatus == HttpStatus.FORBIDDEN){
                return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
            } else {
                throw new RuntimeException();
            }
        }
        /*try {
            coreCategoryEntity = restTemplate.exchange(CATEGORY_SERVICE_URL + "/" + tmpCoreProduct.getCategoryID(),
                    HttpMethod.GET, buildHttpEntity(auth), Category.class);
        } catch (HttpClientErrorException.NotFound ex) {
            return ResponseEntity.notFound().build();
        }*/

        Category tmpCategory = requireNonNull(coreCategoryEntity.getBody());
        LOGGER.info("[InventoryService#getProduct] Got Category " + tmpCategory);

        Product tmpProduct = new Product(tmpCoreProduct.getId(), tmpCoreProduct.getName(), tmpCoreProduct.getPrice(),
                tmpCategory, tmpCoreProduct.getDetails());
        productCache.putIfAbsent(tmpProduct.getId(), tmpProduct);
        LOGGER.info("[InventoryService#getProduct] Return Product " + tmpProduct);
        return ResponseEntity.ok(tmpProduct);
    }

    @SuppressWarnings("unused")
    public ResponseEntity<Product> getProductCache(Long productId, OAuth2Authentication auth) {
        LOGGER.info("[InventoryService#getProductCache] Get product with ID " + productId);
        Product product = productCache.get(productId);
        if (product == null) {
            return ResponseEntity.notFound().build();
        }
        LOGGER.info("[InventoryService#getProductCache] Return product " + product);
        return ResponseEntity.ok(product);
    }

    @HystrixCommand(fallbackMethod = "getProductsCache", commandProperties = {
            @HystrixProperty(name = "circuitBreaker.requestVolumeThreshold", value = "2"),
    })
    @GetMapping("/products")
    @RolesAllowed({"USER"})
    public ResponseEntity<List<Product>> getProducts(@RequestParam(defaultValue = "") String text,
                                                     @RequestParam(defaultValue = MIN_PRICE) Double minPrice,
                                                     @RequestParam(defaultValue = MAX_PRICE) Double maxPrice,
                                                     OAuth2Authentication auth) {
        LOGGER.info("Get products");
        ResponseEntity<CoreProduct[]> coreProductsEntity;

        try {
            coreProductsEntity = restTemplate.exchange(PRODUCT_SERVICE_URL, HttpMethod.GET, buildHttpEntity(auth),
                    CoreProduct[].class);
        } catch (OAuth2Exception e){
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        } catch (Exception ex) {
            LOGGER.warning("[InventoryService#getProduct] Exception: " + ex.getMessage() + "\n" + ex.getStackTrace());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        List<CoreProduct> coreProducts = Arrays.stream(requireNonNull(coreProductsEntity.getBody()))
                .filter(product -> (product.getName().contains(text) || product.getDetails().contains(text))
                        && product.getPrice() <= maxPrice
                        && product.getPrice() >= minPrice)
                .collect(Collectors.toList());

        LOGGER.info("[InventoryService#getProducts] Found " + coreProducts.size() + " core products.");

        ResponseEntity<List<Category>> coreCategoriesEntity = getCategories(auth);
        List<Category> categories = requireNonNull(coreCategoriesEntity.getBody());
        Map<Integer, Category> categoryCoreMap = categories.stream()
                .collect(Collectors.toMap(Category::getId, category -> category, (a, b) -> b));

        List<Product> products = coreProducts.stream()
                .map((CoreProduct coreProduct) -> {
                    Category coreCategory = categoryCoreMap.get(coreProduct.getCategoryID());
                    return new Product(coreProduct.getId(), coreProduct.getName(), coreProduct.getPrice(), coreCategory,
                            coreProduct.getDetails());
                })
                .collect(Collectors.toList());

        productCache.clear();
        products.forEach(product -> productCache.put(product.getId(), product));
        LOGGER.info("[InventoryService#getProducts] Return " + products.size() + " products.");
        return ResponseEntity.ok(products);
    }

    @SuppressWarnings("unused")
    public ResponseEntity<List<Product>> getProductsCache(String text, Double minPrice, Double maxPrice, OAuth2Authentication auth) {
        LOGGER.info("[InventoryService#getProductsCache] Get cached products.");
        List<Product> products = productCache.values()
                .stream()
                .filter(product -> (product.getName().contains(text) || product.getDetails().contains(text))
                        && product.getPrice() <= maxPrice
                        && product.getPrice() >= minPrice)
                .collect(Collectors.toList());
        LOGGER.info("[InventoryService#getProducts] Return " + products.size() + " products.");
        return ResponseEntity.ok(products);
    }

    @HystrixCommand(fallbackMethod = "newProductCache", commandProperties = {
            @HystrixProperty(name = "circuitBreaker.requestVolumeThreshold", value = "2")
    })
    @PostMapping("/products")
    @RolesAllowed({"ADMIN"})
    public ResponseEntity<Product> newProduct(@RequestBody @Valid ProductDTO productDTO, OAuth2Authentication auth) {
        ResponseEntity<Category> categoryResponseEntity = getOrCreateCategory(productDTO.getCategory(), auth);
        if (!wasCallSuccessful(categoryResponseEntity)) {
            return ResponseEntity.status(categoryResponseEntity.getStatusCode()).build();
        }
        Category category = requireNonNull(categoryResponseEntity.getBody());

        CoreProduct newCoreProduct = new CoreProduct(0, productDTO.getName(), productDTO.getPrice(), category.getId(),
                productDTO.getDetails());

        ResponseEntity<CoreProduct> coreProductResponseEntity = restTemplate.exchange(PRODUCT_SERVICE_URL, HttpMethod.POST,
                buildHttpEntity(auth, newCoreProduct), CoreProduct.class);
        if (!wasCallSuccessful(coreProductResponseEntity)) {
            return ResponseEntity.status(coreProductResponseEntity.getStatusCode()).build();
        }
        CoreProduct coreProduct = requireNonNull(coreProductResponseEntity.getBody());
        Product product = new Product(coreProduct.getId(), coreProduct.getName(), coreProduct.getPrice(), category,
                coreProduct.getDetails());
        return ResponseEntity.status(HttpStatus.CREATED).body(product);
    }

    @SuppressWarnings("unused")
    public ResponseEntity<Product> newProductCache(@RequestBody @Valid ProductDTO productDTO, OAuth2Authentication auth) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(null);
    }

    @HystrixCommand(fallbackMethod = "updateProductCache", commandProperties = {
            @HystrixProperty(name = "circuitBreaker.requestVolumeThreshold", value = "2")
    })
    @PutMapping("/products/{id}")
    @RolesAllowed({"ADMIN"})
    public ResponseEntity<Product> updateProduct(@PathVariable(value = "id") Integer productId,
                                                 @RequestBody @Valid Product productToUpdate,
                                                 OAuth2Authentication auth) {
        CoreProduct coreProductToUpdate = new CoreProduct(productToUpdate.getId(), productToUpdate.getName(),
                productToUpdate.getPrice(), productToUpdate.getCategory().getId(), productToUpdate.getDetails());
        ResponseEntity<CoreProduct> coreProductResponseEntity;
        try {
            coreProductResponseEntity = restTemplate.exchange(PRODUCT_SERVICE_URL + "/" + productId, HttpMethod.PUT,
                    buildHttpEntity(auth, coreProductToUpdate), CoreProduct.class);
        } catch (HttpClientErrorException.NotFound ex) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        } catch (OAuth2Exception ex){
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        CoreProduct coreProduct = requireNonNull(coreProductResponseEntity.getBody());

        ResponseEntity<Category> coreCategoryEntity;
        try {
            coreCategoryEntity = restTemplate.exchange(CATEGORY_SERVICE_URL + "/" + coreProduct.getCategoryID(),
                    HttpMethod.GET, buildHttpEntity(auth), Category.class);
        } catch (HttpClientErrorException.NotFound ex) {
            return ResponseEntity.notFound().build();
        } catch (OAuth2Exception ex){
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        Category tmpCategory = requireNonNull(coreCategoryEntity.getBody());

        Product tmpProduct = new Product(coreProduct.getId(), coreProduct.getName(), coreProduct.getPrice(),
                tmpCategory, coreProduct.getDetails());
        productCache.put(tmpProduct.getId(), tmpProduct);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(tmpProduct);
    }

    @SuppressWarnings("unused")
    public ResponseEntity<Product> updateProductCache(@PathVariable(value = "id") Integer productId,
                                                      @RequestBody @Valid Product productToUpdate,
                                                      OAuth2Authentication auth){
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(null);
    }

    @HystrixCommand(fallbackMethod = "deleteProductCache", commandProperties = {
            @HystrixProperty(name = "circuitBreaker.requestVolumeThreshold", value = "2")
    })
    @DeleteMapping("/products/{id}")
    @RolesAllowed({"ADMIN"})
    public ResponseEntity<Product> deleteProduct(@PathVariable(value = "id") Integer productId, OAuth2Authentication auth) {
        ResponseEntity<CoreProduct> coreProductResponseEntity;
        try {
            coreProductResponseEntity = restTemplate.exchange(PRODUCT_SERVICE_URL + "/" + productId,
                    HttpMethod.DELETE, buildHttpEntity(auth), CoreProduct.class);
        } catch (HttpClientErrorException.NotFound ex) {
            return ResponseEntity.notFound().build();
        } catch (OAuth2Exception ex) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        CoreProduct coreProduct = requireNonNull(coreProductResponseEntity.getBody());

        ResponseEntity<Category> categoryResponseEntity = getCategory(coreProduct.getCategoryID(), auth);
        /*try {
            categoryResponseEntity = restTemplate.exchange(CATEGORY_SERVICE_URL + "/" + coreProduct.getCategoryID(),
                    HttpMethod.GET, buildHttpEntity(), Category.class);
        } catch (HttpClientErrorException.NotFound ex) {
            return ResponseEntity.notFound().build();
        }*/
        HttpStatus categoryStatus = categoryResponseEntity.getStatusCode();
        if(!wasCallSuccessful(categoryResponseEntity) && categoryStatus != HttpStatus.NOT_FOUND){
            if(categoryStatus == HttpStatus.FORBIDDEN){
                return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
            } else {
                throw new RuntimeException();
            }
        }
        Category category = requireNonNull(categoryResponseEntity.getBody());
        Product product = new Product(coreProduct.getId(), coreProduct.getName(), coreProduct.getPrice(), category,
                coreProduct.getDetails());
        return ResponseEntity.accepted().body(product);
    }

    @SuppressWarnings("unused")
    public ResponseEntity<Product> deleteProductCache(@PathVariable(value = "id") Integer productId, OAuth2Authentication auth) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(null);
    }

    private ResponseEntity<Category> getOrCreateCategory(String categoryName, OAuth2Authentication auth) {
        ResponseEntity<List<Category>> categoriesEntity;
        try {
            categoriesEntity = getCategories(auth);
        } catch (OAuth2Exception ex){
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        List<Category> categories = requireNonNull(categoriesEntity.getBody());

        Optional<Category> categoryOptional = categories.stream()
                .filter(category -> category.getName().equals(categoryName))
                .findFirst();

        return categoryOptional.map(ResponseEntity::ok)
                .orElseGet(() -> createCategory(new CategoryDTO(categoryName), auth));
    }

    @HystrixCommand(fallbackMethod = "createCategoryCache", commandProperties = {
            @HystrixProperty(name = "circuitBreaker.requestVolumeThreshold", value = "2")
    })
    @PostMapping("/categories")
    @RolesAllowed({"ADMIN"})
    public ResponseEntity<Category> createCategory(@RequestBody @Valid CategoryDTO categoryDTO, OAuth2Authentication auth) {
        try {
            return restTemplate.exchange(CATEGORY_SERVICE_URL, HttpMethod.POST, buildHttpEntity(auth, categoryDTO),
                    Category.class);
        } catch (HttpClientErrorException.BadRequest ex) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        } catch (OAuth2Exception ex){
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
    }

    @SuppressWarnings("unused")
    public ResponseEntity<Category> createCategoryCache(@RequestBody @Valid CategoryDTO categoryDTO, OAuth2Authentication auth) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(null);
    }

    @HystrixCommand(fallbackMethod = "getCategoriesCache", commandProperties = {
            @HystrixProperty(name = "circuitBreaker.requestVolumeThreshold", value = "2")
    })
    @GetMapping("/categories")
    @RolesAllowed({"USER"})
    public ResponseEntity<List<Category>> getCategories(OAuth2Authentication auth) {
        LOGGER.info("[InventoryService#getCategories] Get categories.");
        ResponseEntity<Category[]> categoriesEntity;
        try {
            categoriesEntity = restTemplate.exchange(CATEGORY_SERVICE_URL, HttpMethod.GET, buildHttpEntity(auth),
                    Category[].class);
        } catch (OAuth2Exception ex){
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        List<Category> categories = List.of(requireNonNull(categoriesEntity.getBody()));
        LOGGER.info("[InventoryService#getCategories] Found " + categories.size() + " categories.");
        categoryCache.clear();
        categories.forEach(category -> categoryCache.put(category.getId(), category));
        return ResponseEntity.ok(categories);
    }

    @SuppressWarnings("unused")
    public ResponseEntity<List<Category>> getCategoriesCache(OAuth2Authentication auth) {
        LOGGER.info("[InventoryService#getCategoriesCache] Get cached categories.");
        return ResponseEntity.ok(List.copyOf(categoryCache.values()));
    }

    @HystrixCommand(fallbackMethod = "getCategoryCache", commandProperties = {
            @HystrixProperty(name = "circuitBreaker.requestVolumeThreshold", value = "2")
    })
    @GetMapping("categories/{id}")
    @RolesAllowed({"USER"})
    public ResponseEntity<Category> getCategory(@PathVariable(value = "id") Integer categoryId, OAuth2Authentication auth){
        ResponseEntity<Category> categoryEntity;
        try {
            categoryEntity = restTemplate.exchange(CATEGORY_SERVICE_URL + "/" + categoryId,
                    HttpMethod.GET, buildHttpEntity(auth), Category.class);
        } catch (HttpClientErrorException.NotFound ex) {
            return ResponseEntity.notFound().build();
        } catch (OAuth2Exception ex){
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        Category category = requireNonNull(categoryEntity.getBody());
        categoryCache.putIfAbsent(category.getId(), category);
        return ResponseEntity.ok(category);
    }

    @SuppressWarnings("unused")
    public ResponseEntity<Category> getCategoryCache(@PathVariable(value = "id") Long categoryId, OAuth2Authentication auth){
        Category category = categoryCache.get(categoryId);
        if (category == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(category);
    }

    @HystrixCommand(fallbackMethod = "updateCategoryCache", commandProperties = {
            @HystrixProperty(name = "circuitBreaker.requestVolumeThreshold", value = "2")
    })
    @PutMapping("/categories/{id}")
    @RolesAllowed({"ADMIN"})
    public ResponseEntity<Category> updateCategory(@PathVariable(value = "id") Long categoryId,
                                                   @RequestBody @Valid Category categoryToUpdate,
                                                   OAuth2Authentication auth) {
        ResponseEntity<Category> categoryResponseEntity;
        try {
            categoryResponseEntity = restTemplate.exchange(CATEGORY_SERVICE_URL + "/" + categoryId,
                    HttpMethod.PUT, buildHttpEntity(auth, categoryToUpdate), Category.class);
        } catch (OAuth2Exception ex){
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        Category category = requireNonNull(categoryResponseEntity.getBody());
        categoryCache.put(category.getId(), category);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(category);
    }

    @SuppressWarnings("unused")
    public ResponseEntity<Category> updateCategoryCache(@PathVariable(value = "id") Long categoryId,
                                                        @RequestBody @Valid Category categoryToUpdate,
                                                        OAuth2Authentication auth) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(null);
    }

    @HystrixCommand(fallbackMethod = "deleteCategoryCache", commandProperties = {
            @HystrixProperty(name = "circuitBreaker.requestVolumeThreshold", value = "2")
    })
    @DeleteMapping("/categories/{id}")
    @RolesAllowed({"ADMIN"})
    public ResponseEntity<Category> deleteCategory(@PathVariable(value = "id") Long categoryId, OAuth2Authentication auth) {
        ResponseEntity<Category> categoryResponseEntity;
        try {
            categoryResponseEntity = restTemplate.exchange(CATEGORY_SERVICE_URL + "/" + categoryId,
                    HttpMethod.DELETE, buildHttpEntity(auth), Category.class);
        } catch (HttpClientErrorException.NotFound ex) {
            return ResponseEntity.notFound().build();
        } catch (OAuth2Exception ex){
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        Category category = requireNonNull(categoryResponseEntity.getBody());

        ResponseEntity<CoreProduct[]> coreProductsResponseEntity;

        try {
            coreProductsResponseEntity = restTemplate.exchange(PRODUCT_SERVICE_URL, HttpMethod.GET, buildHttpEntity(auth),
                    CoreProduct[].class);
        } catch (OAuth2Exception ex){
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        List<CoreProduct> coreProducts = Arrays.stream(requireNonNull(coreProductsResponseEntity.getBody()))
                .filter(product -> product.getCategoryID() == category.getId())
                .collect(Collectors.toList());
        //Delete all associated products
        coreProducts.forEach(coreProduct -> deleteProduct(coreProduct.getCategoryID(), auth));
        return ResponseEntity.accepted().body(category);
    }

    @SuppressWarnings("unused")
    public ResponseEntity<Category> deleteCategoryCache(@PathVariable(value = "id") Long categoryId, OAuth2Authentication auth) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(null);
    }

    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    private static <T> boolean wasCallSuccessful(ResponseEntity<T> responseEntity) {
        int status = responseEntity.getStatusCodeValue();
        return status >= 200 && status < 300;
    }

    private HttpEntity buildHttpEntity(OAuth2Authentication auth) {
        return buildHttpEntity(auth, null);
    }

    private <T> HttpEntity<T> buildHttpEntity(OAuth2Authentication auth, @Nullable T body) {
        final OAuth2AuthenticationDetails details = (OAuth2AuthenticationDetails) auth.getDetails();
        LOGGER.info("Token is: " + details.getTokenValue());
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(details.getTokenValue());
        return new HttpEntity<>(body, headers);
    }

    /*private <T> HttpEntity<T> buildHttpEntity(@Nullable T body) {
        HttpHeaders headers = new HttpHeaders();
        LOGGER.info("HTTP body should be " + body);
        return new HttpEntity<>(body, headers);
    }

    private <T> HttpEntity<T> buildHttpEntity() {
        HttpHeaders headers = new HttpHeaders();
        return new HttpEntity<>(null, headers);
    }*/

}

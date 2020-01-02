package tech.bestwebshop.api.productcomposite;

import com.netflix.hystrix.contrib.javanica.annotation.HystrixCommand;
import com.netflix.hystrix.contrib.javanica.annotation.HystrixProperty;
import org.springframework.cloud.client.circuitbreaker.EnableCircuitBreaker;
import org.springframework.cloud.netflix.hystrix.EnableHystrix;
import org.springframework.http.*;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import tech.bestwebshop.api.productcomposite.model.*;

import javax.validation.Valid;
import java.util.*;
import java.util.stream.Collectors;

import static java.util.Objects.requireNonNull;

@Component
@EnableHystrix
@RestController
@EnableCircuitBreaker
@CrossOrigin(origins = "*", allowedHeaders = "*")
public class InventoryController {
    private final Map<Integer, Product> productCache = new LinkedHashMap<>();
    private final Map<Integer, Category> categoryCache = new LinkedHashMap<>();

    private static final String PRODUCT_SERVICE_URL = "http://product-service:8080/products";
    private static final String CATEGORY_SERVICE_URL = "http://category-service:8080/categories";
    private static final String MAX_PRICE = "1e10";
    private static final String MIN_PRICE = "-1e10";

    private static final RestTemplate restTemplate = new RestTemplate();

    @HystrixCommand(fallbackMethod = "getProductCache", commandProperties = {
            @HystrixProperty(name = "circuitBreaker.requestVolumeThreshold", value = "2")
    })
    @GetMapping("/products/{id}")
    public ResponseEntity<Product> getProduct(@PathVariable(value = "id") Long productId) {
        ResponseEntity<CoreProduct> coreProductEntity;
        try {
            coreProductEntity = restTemplate.exchange(PRODUCT_SERVICE_URL + "/" + productId,
                    HttpMethod.GET, buildHttpEntity(), CoreProduct.class);
        } catch (HttpClientErrorException.NotFound ex) {
            return ResponseEntity.notFound().build();
        }
        CoreProduct tmpCoreProduct = requireNonNull(coreProductEntity.getBody());

        ResponseEntity<Category> coreCategoryEntity;
        try {
            coreCategoryEntity = restTemplate.exchange(CATEGORY_SERVICE_URL + "/" + tmpCoreProduct.getCategoryID(),
                    HttpMethod.GET, buildHttpEntity(), Category.class);
        } catch (HttpClientErrorException.NotFound ex) {
            return ResponseEntity.notFound().build();
        }

        Category tmpCategory = requireNonNull(coreCategoryEntity.getBody());
        System.out.println("Found category: " + tmpCategory);

        Product tmpProduct = new Product(tmpCoreProduct.getId(), tmpCoreProduct.getName(), tmpCoreProduct.getPrice(),
                tmpCategory, tmpCoreProduct.getDetails());
        productCache.putIfAbsent(tmpProduct.getId(), tmpProduct);
        return ResponseEntity.ok(tmpProduct);
    }

    @SuppressWarnings("unused")
    public ResponseEntity<Product> getProductCache(Long productId) {
        Product product = productCache.get(productId);
        if (product == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(product);
    }

    @HystrixCommand(fallbackMethod = "getProductsCache", commandProperties = {
            @HystrixProperty(name = "circuitBreaker.requestVolumeThreshold", value = "2")
    })
    @GetMapping("/products")
    public ResponseEntity<List<Product>> getProducts(@RequestParam(defaultValue = "") String text,
                                                     @RequestParam(defaultValue = MIN_PRICE) Double minPrice,
                                                     @RequestParam(defaultValue = MAX_PRICE) Double maxPrice) {
        ResponseEntity<CoreProduct[]> coreProductsEntity;

        coreProductsEntity = restTemplate.exchange(PRODUCT_SERVICE_URL, HttpMethod.GET, buildHttpEntity(),
                CoreProduct[].class);

        List<CoreProduct> coreProducts = Arrays.stream(requireNonNull(coreProductsEntity.getBody()))
                .filter(product -> (product.getName().contains(text) || product.getDetails().contains(text))
                        && product.getPrice() <= maxPrice
                        && product.getPrice() >= minPrice)
                .collect(Collectors.toList());

        ResponseEntity<List<Category>> coreCategoriesEntity = getCategories();
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
        return ResponseEntity.ok(products);
    }

    @SuppressWarnings("unused")
    public ResponseEntity<List<Product>> getProductsCache(String text, Double minPrice, Double maxPrice) {
        List<Product> products = productCache.values()
                .stream()
                .filter(product -> (product.getName().contains(text) || product.getDetails().contains(text))
                        && product.getPrice() <= maxPrice
                        && product.getPrice() >= minPrice)
                .collect(Collectors.toList());
        return ResponseEntity.ok(products);
    }

    @HystrixCommand(fallbackMethod = "newProductCache", commandProperties = {
            @HystrixProperty(name = "circuitBreaker.requestVolumeThreshold", value = "2")
    })
    @PostMapping("/products")
    public ResponseEntity<Product> newProduct(@RequestBody @Valid ProductDTO productDTO) {
        ResponseEntity<Category> categoryResponseEntity = getOrCreateCategory(productDTO.getCategory());
        if (!wasCallSuccessful(categoryResponseEntity)) {
            return ResponseEntity.status(categoryResponseEntity.getStatusCode()).build();
        }
        Category category = requireNonNull(categoryResponseEntity.getBody());

        CoreProduct newCoreProduct = new CoreProduct(0, productDTO.getName(), productDTO.getPrice(), category.getId(),
                productDTO.getDetails());

        ResponseEntity<CoreProduct> coreProductResponseEntity = restTemplate.exchange(PRODUCT_SERVICE_URL, HttpMethod.POST,
                buildHttpEntity(newCoreProduct), CoreProduct.class);
        if (!wasCallSuccessful(coreProductResponseEntity)) {
            return ResponseEntity.status(coreProductResponseEntity.getStatusCode()).build();
        }
        CoreProduct coreProduct = requireNonNull(coreProductResponseEntity.getBody());
        Product product = new Product(coreProduct.getId(), coreProduct.getName(), coreProduct.getPrice(), category,
                coreProduct.getDetails());
        return ResponseEntity.status(HttpStatus.CREATED).body(product);
    }

    @SuppressWarnings("unused")
    public ResponseEntity<Product> newProductCache(@RequestBody @Valid ProductDTO productDTO) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(null);
    }

    @HystrixCommand(fallbackMethod = "deleteProductCache", commandProperties = {
            @HystrixProperty(name = "circuitBreaker.requestVolumeThreshold", value = "2")
    })
    @DeleteMapping("/products/{id}")
    public ResponseEntity<Product> deleteProduct(@PathVariable(value = "id") Integer productId) {
        ResponseEntity<CoreProduct> coreProductResponseEntity;
        try {
            coreProductResponseEntity = restTemplate.exchange(PRODUCT_SERVICE_URL + "/" + productId,
                    HttpMethod.DELETE, buildHttpEntity(), CoreProduct.class);
        } catch (HttpClientErrorException.NotFound ex) {
            return ResponseEntity.notFound().build();
        }
        CoreProduct coreProduct = requireNonNull(coreProductResponseEntity.getBody());

        ResponseEntity<Category> categoryResponseEntity;
        try {
            categoryResponseEntity = restTemplate.exchange(CATEGORY_SERVICE_URL + "/" + coreProduct.getCategoryID(),
                    HttpMethod.GET, buildHttpEntity(), Category.class);
        } catch (HttpClientErrorException.NotFound ex) {
            return ResponseEntity.notFound().build();
        }
        Category category = requireNonNull(categoryResponseEntity.getBody());
        Product product = new Product(coreProduct.getId(), coreProduct.getName(), coreProduct.getPrice(), category,
                coreProduct.getDetails());
        return ResponseEntity.accepted().body(product);
    }

    @SuppressWarnings("unused")
    public ResponseEntity<Product> deleteProductCache(@PathVariable(value = "id") Integer productId) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(null);
    }

    private ResponseEntity<Category> getOrCreateCategory(String categoryName) {
        ResponseEntity<List<Category>> categoriesEntity;
        categoriesEntity = getCategories();

        List<Category> categories = requireNonNull(categoriesEntity.getBody());

        Optional<Category> categoryOptional = categories.stream()
                .filter(category -> category.getName().equals(categoryName))
                .findFirst();

        return categoryOptional.map(ResponseEntity::ok)
                .orElseGet(() -> createCategory(new CategoryDTO(categoryName)));
    }

    @HystrixCommand(fallbackMethod = "createCategoryCache", commandProperties = {
            @HystrixProperty(name = "circuitBreaker.requestVolumeThreshold", value = "2")
    })
    @PostMapping("/categories")
    public ResponseEntity<Category> createCategory(@RequestBody @Valid CategoryDTO categoryDTO) {
        try {
            return restTemplate.exchange(CATEGORY_SERVICE_URL, HttpMethod.POST, buildHttpEntity(categoryDTO),
                    Category.class);
        } catch (HttpClientErrorException.BadRequest ex) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }
    }

    @SuppressWarnings("unused")
    public ResponseEntity<Category> createCategoryCache(@RequestBody @Valid CategoryDTO categoryDTO) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(null);
    }

    @HystrixCommand(fallbackMethod = "getCategoriesCache", commandProperties = {
            @HystrixProperty(name = "circuitBreaker.requestVolumeThreshold", value = "2")
    })
    @GetMapping("/categories")
    public ResponseEntity<List<Category>> getCategories() {
        ResponseEntity<Category[]> categoriesEntity;
        categoriesEntity = restTemplate.exchange(CATEGORY_SERVICE_URL, HttpMethod.GET, buildHttpEntity(),
                Category[].class);
        List<Category> categories = List.of(requireNonNull(categoriesEntity.getBody()));
        categoryCache.clear();
        categories.forEach(category -> categoryCache.put(category.getId(), category));
        return ResponseEntity.ok(categories);
    }

    @SuppressWarnings("unused")
    public ResponseEntity<List<Category>> getCategoriesCache() {
        return ResponseEntity.ok(List.copyOf(categoryCache.values()));
    }

    @HystrixCommand(fallbackMethod = "deleteCategoryCache", commandProperties = {
            @HystrixProperty(name = "circuitBreaker.requestVolumeThreshold", value = "2")
    })
    @DeleteMapping("/categories/{id}")
    public ResponseEntity<Category> deleteCategory(@PathVariable(value = "id") Long categoryId) {
        ResponseEntity<Category> categoryResponseEntity;
        try {
            categoryResponseEntity = restTemplate.exchange(CATEGORY_SERVICE_URL + "/" + categoryId,
                    HttpMethod.DELETE, buildHttpEntity(), Category.class);
        } catch (HttpClientErrorException.NotFound ex) {
            return ResponseEntity.notFound().build();
        }
        Category category = requireNonNull(categoryResponseEntity.getBody());

        ResponseEntity<CoreProduct[]> coreProductsResponseEntity;

        coreProductsResponseEntity = restTemplate.exchange(PRODUCT_SERVICE_URL, HttpMethod.GET, buildHttpEntity(),
                CoreProduct[].class);

        List<CoreProduct> coreProducts = Arrays.stream(requireNonNull(coreProductsResponseEntity.getBody()))
                .filter(product -> product.getCategoryID() == category.getId())
                .collect(Collectors.toList());
        //Delete all associated products
        coreProducts.forEach(coreProduct -> deleteProduct(coreProduct.getCategoryID()));
        return ResponseEntity.accepted().body(category);
    }

    @SuppressWarnings("unused")
    public ResponseEntity<Category> deleteCategoryCache(@PathVariable(value = "id") Long categoryId) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(null);
    }

    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    private static <T> boolean wasCallSuccessful(ResponseEntity<T> responseEntity) {
        int status = responseEntity.getStatusCodeValue();
        return status >= 200 && status < 300;
    }

    /*private HttpEntity buildHttpEntity(OAuth2Authentication auth) {
        return buildHttpEntity(auth, null);
    }

    private <T> HttpEntity<T> buildHttpEntity(OAuth2Authentication auth, @Nullable T body) {
        final OAuth2AuthenticationDetails details = (OAuth2AuthenticationDetails) auth.getDetails();
        LOGGER.info("Token is: " + details.getTokenValue());
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(details.getTokenValue());
        return new HttpEntity<>(body, headers);
    }*/

    private <T> HttpEntity<T> buildHttpEntity(@Nullable T body) {
        HttpHeaders headers = new HttpHeaders();
        System.out.println("HTTP body should be " + body);
        return new HttpEntity<>(body, headers);
    }

    private <T> HttpEntity<T> buildHttpEntity() {
        HttpHeaders headers = new HttpHeaders();
        return new HttpEntity<>(null, headers);
    }

}

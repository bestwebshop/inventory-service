package tech.bestwebshop.api.productcomposite;

import com.netflix.hystrix.contrib.javanica.annotation.HystrixCommand;
import com.netflix.hystrix.contrib.javanica.annotation.HystrixProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.netflix.hystrix.EnableHystrix;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import tech.bestwebshop.api.productcomposite.model.Category;
import tech.bestwebshop.api.productcomposite.model.CoreProduct;
import tech.bestwebshop.api.productcomposite.model.Product;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static java.util.Objects.requireNonNull;

@Component
@EnableHystrix
@RestController
@RequestMapping("/")
public class InventoryController {
    private final Map<Integer, Product> productCache = new LinkedHashMap<>();
    private final Map<Integer, Category> categoryCache = new LinkedHashMap<>();

    private static final String PRODUCT_SERVICE_URL = "http://product-service:8080/products";
    private static final String CATEGORY_SERVICE_URL = "http://category-service:8080/categories";

    @Autowired
    private RestTemplate restTemplate;

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
        System.out.println("Found product: " + tmpCoreProduct);

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
                                                     @RequestParam(defaultValue = "-1e10") Double minPrice,
                                                     @RequestParam(defaultValue = "1e10") Double maxPrice) {
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

    @GetMapping("/categories")
    private ResponseEntity<List<Category>> getCategories() {
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

   /* @PostMapping("/products")
    public Product createProduct(@RequestBody @Valid Product product){
        ProductCore productCore = new ProductCore(product.getName(), product.getPrice(), product.getCategory().get)

        return product;
    }*/

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

    private <T> HttpEntity<T> buildHttpEntity() {
        HttpHeaders headers = new HttpHeaders();
        return new HttpEntity<>(null, headers);
    }

}

package tech.bestwebshop.api.productcomposite;

import lombok.*;

@Data
@RequiredArgsConstructor
public class ProductCore {

    @Generated
    private int id;
    @NonNull
    private String name;
    @NonNull
    private double price;
    @NonNull
    private int categoryID;
    @NonNull
    private String details;
}

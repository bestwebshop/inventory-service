package tech.bestwebshop.api.productcomposite;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

@Data
@NoArgsConstructor
@RequiredArgsConstructor
public class CategoryCore {

    @NonNull
    private int id;
    @NonNull
    private String name;
}

package global.store;

import global.N8VRate;

/**
 * Created by furszy on 3/3/18.
 */

public interface RateDbDao<T> extends AbstractDbDao<T>{

    N8VRate getRate(String coin);


    void insertOrUpdateIfExist(N8VRate n8VRate);

}

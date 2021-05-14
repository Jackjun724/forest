package com.retzero.forest.auto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @author JackJun
 * @date 2021/1/4 下午12:35
 */
@Data
public class Personal {
    //标志
    private String name;
    //授权信息
    private String cookie;
    //设备信息
    private String miniwua;
    //运行期间总共偷取的能量
    private int allTotalEnergy;
    //运行期间总共帮好友收取的能量
    private int allTotalForFriendEnergy;

    public Personal(String name, String cookie,String miniwua){
        this.name = name;
        this.cookie = cookie;
        this.miniwua = miniwua;
        this.allTotalEnergy = 0;
        this.allTotalForFriendEnergy = 0;
    }
}
